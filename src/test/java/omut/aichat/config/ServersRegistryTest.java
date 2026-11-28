package omut.aichat.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ServersRegistryTest {

    @TempDir
    Path tmp;

    private Path configFile() {
        return tmp.resolve("config.properties");
    }

    private UserConfig userConfig() {
        return new UserConfig(configFile());
    }

    private ServersRegistry registry(UserConfig userConfig) {
        return new ServersRegistry(userConfig, new AppDefaults().baseUrl());
    }

    private Properties readBack() throws IOException {
        Properties p = new Properties();
        try (var reader = Files.newBufferedReader(configFile())) {
            p.load(reader);
        }
        return p;
    }

    @Nested
    @DisplayName("Legacy URL migration")
    class Migration {

        @Test
        @DisplayName("Legacy URL becomes the 'local' server")
        void legacyUrlBecomesLocal() throws IOException {
            Files.createDirectories(tmp);
            Files.writeString(configFile(),
                    ConfigKeys.OLLAMA_BASE_URL + "=http://10.0.0.5:8080\n");

            ServersRegistry registry = registry(userConfig());

            assertThat(registry.serverIds())
                    .containsExactly(AppConfig.LOCAL_SERVER_ID);
            assertThat(registry.activeServerId())
                    .isEqualTo(AppConfig.LOCAL_SERVER_ID);
            assertThat(registry.serverBaseUrl(AppConfig.LOCAL_SERVER_ID))
                    .isEqualTo("http://10.0.0.5:8080");

            Properties saved = readBack();
            assertThat(saved.getProperty(
                    ConfigKeys.serverKey("local", ConfigKeys.SERVER_HOST_SUFFIX)))
                    .isEqualTo("10.0.0.5");
            assertThat(saved.getProperty(
                    ConfigKeys.serverKey("local", ConfigKeys.SERVER_PORT_SUFFIX)))
                    .isEqualTo("8080");
            assertThat(saved.getProperty(
                    ConfigKeys.serverKey("local", ConfigKeys.SERVER_PROTOCOL_SUFFIX)))
                    .isEqualTo("http");
        }

        @Test
        @DisplayName("URL without a port gets the default 11434")
        void defaultPort() throws IOException {
            Files.createDirectories(tmp);
            Files.writeString(configFile(),
                    ConfigKeys.OLLAMA_BASE_URL + "=http://192.168.1.5\n");

            ServersRegistry registry = registry(userConfig());

            assertThat(registry.serverBaseUrl(AppConfig.LOCAL_SERVER_ID))
                    .isEqualTo("http://192.168.1.5:11434");
        }

        @Test
        @DisplayName("Migration runs only once; later initialization keeps the server list")
        void notRerun() {
            UserConfig userConfig = userConfig();
            ServersRegistry first = registry(userConfig);

            String id = first.addServer("Home", "10.0.0.10", 11434);

            ServersRegistry second = registry(userConfig());

            assertThat(second.serverIds())
                    .containsExactly(AppConfig.LOCAL_SERVER_ID, id);
            assertThat(second.serverName(id)).isEqualTo("Home");
        }
    }

    @Nested
    @DisplayName("Self-heal")
    class SelfHeal {

        @Test
        @DisplayName("Missing 'local' is restored at the head of the list")
        void missingLocalRestored() throws IOException {
            Files.createDirectories(tmp);
            Files.writeString(configFile(),
                    ConfigKeys.OLLAMA_SERVER_LIST + "=srv-aaaa,srv-bbbb\n"
                            + ConfigKeys.OLLAMA_ACTIVE_SERVER + "=srv-aaaa\n");

            ServersRegistry registry = registry(userConfig());

            assertThat(registry.serverIds())
                    .containsExactly(
                            AppConfig.LOCAL_SERVER_ID,
                            "srv-aaaa",
                            "srv-bbbb");
            assertThat(registry.activeServerId()).isEqualTo("srv-aaaa");
        }

        @Test
        @DisplayName("Active pointing at a missing id falls back to 'local'")
        void brokenActiveReset() throws IOException {
            Files.createDirectories(tmp);
            Files.writeString(configFile(),
                    ConfigKeys.OLLAMA_SERVER_LIST + "=local\n"
                            + ConfigKeys.OLLAMA_ACTIVE_SERVER + "=srv-gone\n");

            ServersRegistry registry = registry(userConfig());

            assertThat(registry.activeServerId())
                    .isEqualTo(AppConfig.LOCAL_SERVER_ID);
        }

        @Test
        @DisplayName("Missing 'local' fields are filled from bundled defaults")
        void missingFieldsFilled() throws IOException {
            Files.createDirectories(tmp);
            Files.writeString(configFile(),
                    ConfigKeys.OLLAMA_SERVER_LIST + "=local\n"
                            + ConfigKeys.serverKey("local", ConfigKeys.SERVER_HOST_SUFFIX)
                            + "=192.168.1.5\n");

            ServersRegistry registry = registry(userConfig());

            Properties saved = readBack();
            assertThat(saved.getProperty(
                    ConfigKeys.serverKey("local", ConfigKeys.SERVER_NAME_SUFFIX)))
                    .isNotBlank();
            assertThat(saved.getProperty(
                    ConfigKeys.serverKey("local", ConfigKeys.SERVER_PORT_SUFFIX)))
                    .isEqualTo("11434");
            assertThat(registry.serverBaseUrl("local"))
                    .isEqualTo("http://192.168.1.5:11434");
        }

        @Test
        @DisplayName("Adding a server after self-heal keeps 'local' first")
        void addAfterSelfHeal() throws IOException {
            Files.createDirectories(tmp);
            Files.writeString(configFile(),
                    ConfigKeys.OLLAMA_SERVER_LIST + "=srv-aaaa\n"
                            + ConfigKeys.OLLAMA_ACTIVE_SERVER + "=srv-aaaa\n");

            ServersRegistry registry = registry(userConfig());

            String newId = registry.addServer("New", "1.2.3.4", 11434);

            assertThat(registry.serverIds())
                    .containsExactly(AppConfig.LOCAL_SERVER_ID, "srv-aaaa", newId);
        }
    }

    @Nested
    @DisplayName("addServer")
    class AddServer {

        @Test
        @DisplayName("New id has 'srv-' prefix and does not collide")
        void generatesId() {
            ServersRegistry registry = registry(userConfig());

            String id = registry.addServer("Home", "10.0.0.10", 11434);

            assertThat(id).startsWith("srv-");
            assertThat(registry.serverIds()).contains(id);
            assertThat(registry.serverIds()).hasSize(2);
        }

        @Test
        @DisplayName("Fields are persisted")
        void persistsFields() {
            ServersRegistry registry = registry(userConfig());

            String id = registry.addServer("Home", "10.0.0.10", 11434);

            assertThat(registry.serverName(id)).isEqualTo("Home");
            assertThat(registry.serverBaseUrl(id))
                    .isEqualTo("http://10.0.0.10:11434");
        }

        @Test
        @DisplayName("Adding a server does not change the active one")
        void activeUnchanged() {
            ServersRegistry registry = registry(userConfig());

            registry.addServer("Home", "10.0.0.10", 11434);

            assertThat(registry.activeServerId())
                    .isEqualTo(AppConfig.LOCAL_SERVER_ID);
        }

        @Test
        @DisplayName("100 servers produce 100 unique ids")
        void uniqueIds() {
            ServersRegistry registry = registry(userConfig());
            Set<String> ids = new HashSet<>();
            ids.add(AppConfig.LOCAL_SERVER_ID);

            for (int i = 0; i < 100; i++) {
                String id = registry.addServer("S" + i, "127.0.0.1", 11434);

                assertThat(ids.add(id))
                        .as("duplicate id generated: " + id)
                        .isTrue();
            }

            assertThat(registry.serverIds()).hasSize(101);
            assertThat(ids).hasSize(101);
        }
    }

    @Nested
    @DisplayName("updateServer")
    class UpdateServer {

        @Test
        @DisplayName("Renaming updates the display name only")
        void rename() {
            ServersRegistry registry = registry(userConfig());

            registry.updateServer(AppConfig.LOCAL_SERVER_ID, "My Ollama",
                    "127.0.0.1", 11434);

            assertThat(registry.serverName(AppConfig.LOCAL_SERVER_ID))
                    .isEqualTo("My Ollama");
            assertThat(registry.serverBaseUrl(AppConfig.LOCAL_SERVER_ID))
                    .isEqualTo("http://127.0.0.1:11434");
        }

        @Test
        @DisplayName("Unknown id is a no-op")
        void unknownId() {
            ServersRegistry registry = registry(userConfig());

            registry.updateServer("srv-nope", "X", "1.2.3.4", 1234);

            assertThat(registry.serverIds()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("removeServer")
    class RemoveServer {

        @Test
        @DisplayName("Removes the server and its fields")
        void removesFields() {
            UserConfig userConfig = userConfig();
            ServersRegistry registry = registry(userConfig);

            String id = registry.addServer("Home", "10.0.0.10", 11434);
            registry.removeServer(id);

            assertThat(registry.serverIds()).doesNotContain(id);
            assertThat(registry.serverBaseUrl(id)).isNull();
        }

        @Test
        @DisplayName("'local' cannot be removed")
        void localProtected() {
            ServersRegistry registry = registry(userConfig());

            registry.removeServer(AppConfig.LOCAL_SERVER_ID);

            assertThat(registry.serverIds())
                    .contains(AppConfig.LOCAL_SERVER_ID);
        }

        @Test
        @DisplayName("Removing the active server falls back to 'local'")
        void activeFallback() {
            ServersRegistry registry = registry(userConfig());

            String id = registry.addServer("Home", "10.0.0.10", 11434);
            registry.setActiveServer(id);

            assertThat(registry.activeServerId()).isEqualTo(id);

            registry.removeServer(id);

            assertThat(registry.activeServerId())
                    .isEqualTo(AppConfig.LOCAL_SERVER_ID);
        }
    }

    @Nested
    @DisplayName("setActiveServer")
    class SetActiveServer {

        @Test
        @DisplayName("Switching updates the active server URL")
        void switchChangesBaseUrl() {
            ServersRegistry registry = registry(userConfig());

            String id = registry.addServer("Home", "10.0.0.10", 8080);
            registry.setActiveServer(id);

            assertThat(registry.activeServerId()).isEqualTo(id);
            assertThat(registry.activeServerBaseUrl())
                    .isEqualTo("http://10.0.0.10:8080");
        }

        @Test
        @DisplayName("Unknown id is a no-op")
        void unknownId() {
            ServersRegistry registry = registry(userConfig());

            registry.setActiveServer("srv-nope");

            assertThat(registry.activeServerId())
                    .isEqualTo(AppConfig.LOCAL_SERVER_ID);
        }

        @Test
        @DisplayName("Blank id is a no-op")
        void blankId() {
            ServersRegistry registry = registry(userConfig());

            String id = registry.addServer("Home", "10.0.0.10", 8080);
            registry.setActiveServer(id);

            registry.setActiveServer("   ");

            assertThat(registry.activeServerId()).isEqualTo(id);
        }
    }

    @Nested
    @DisplayName("serverBaseUrl")
    class ServerBaseUrl {

        @Test
        @DisplayName("Unknown id returns null")
        void unknown() {
            ServersRegistry registry = registry(userConfig());

            assertThat(registry.serverBaseUrl("srv-nope")).isNull();
        }

        @Test
        @DisplayName("Local server URL matches the bundled default on a fresh config")
        void localMatchesDefault() {
            ServersRegistry registry = registry(userConfig());

            assertThat(registry.serverBaseUrl(AppConfig.LOCAL_SERVER_ID))
                    .isEqualTo(new AppDefaults().baseUrl());
        }
    }

    @Nested
    @DisplayName("serverName")
    class ServerName {

        @Test
        @DisplayName("Unknown id returns the id itself")
        void unknownId() {
            ServersRegistry registry = registry(userConfig());

            assertThat(registry.serverName("srv-nope")).isEqualTo("srv-nope");
        }

        @Test
        @DisplayName("Configured name wins over id")
        void configured() {
            ServersRegistry registry = registry(userConfig());

            String id = registry.addServer("Work", "10.0.0.10", 11434);

            assertThat(registry.serverName(id)).isEqualTo("Work");
        }
    }

    @Nested
    @DisplayName("serverIds order")
    class Order {

        @Test
        @DisplayName("Local is always first")
        void localFirst() {
            ServersRegistry registry = registry(userConfig());

            registry.addServer("A", "1.1.1.1", 1);
            registry.addServer("B", "2.2.2.2", 2);

            assertThat(registry.serverIds().getFirst())
                    .isEqualTo(AppConfig.LOCAL_SERVER_ID);
        }

        @Test
        @DisplayName("New servers are appended in insertion order")
        void insertionOrder() {
            ServersRegistry registry = registry(userConfig());

            String a = registry.addServer("A", "1.1.1.1", 1);
            String b = registry.addServer("B", "2.2.2.2", 2);

            assertThat(registry.serverIds())
                    .containsExactly(AppConfig.LOCAL_SERVER_ID, a, b);
        }
    }

    @Nested
    @DisplayName("Idempotent initialization")
    class Idempotent {

        @Test
        @DisplayName("Second initialization does not reset existing server data")
        void secondInitDoesNotResetLocal() {
            UserConfig firstConfig = userConfig();
            ServersRegistry first = registry(firstConfig);

            first.updateServer(AppConfig.LOCAL_SERVER_ID, "My local",
                    "192.168.1.5", 9000);

            ServersRegistry second = registry(userConfig());

            assertThat(second.serverIds())
                    .containsExactly(AppConfig.LOCAL_SERVER_ID);
            assertThat(second.serverName(AppConfig.LOCAL_SERVER_ID))
                    .isEqualTo("My local");
            assertThat(second.serverBaseUrl(AppConfig.LOCAL_SERVER_ID))
                    .isEqualTo("http://192.168.1.5:9000");
        }
    }
}
