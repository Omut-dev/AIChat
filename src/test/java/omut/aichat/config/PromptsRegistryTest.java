
package omut.aichat.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PromptsRegistryTest {

    private static final String DEFAULT_SYSTEM_PROMPT = "Bundled default prompt";

    @TempDir
    Path tmp;

    private Path configFile() {
        return tmp.resolve("config.properties");
    }

    private UserConfig userConfig() {
        return new UserConfig(configFile());
    }

    private PromptsRegistry registry(UserConfig userConfig) {
        return new PromptsRegistry(userConfig, DEFAULT_SYSTEM_PROMPT);
    }

    @Nested
    @DisplayName("Legacy prompt migration")
    class Migration {

        @Test
        @DisplayName("Legacy system prompt becomes the 'default' prompt")
        void legacyPromptBecomesDefault() throws IOException {
            Files.createDirectories(tmp);
            Files.writeString(configFile(),
                    "chat.system.prompt=Legacy system prompt\n");

            PromptsRegistry registry = registry(userConfig());

            assertThat(registry.promptIds())
                    .containsExactly(PromptsRegistry.DEFAULT_PROMPT_ID);
            assertThat(registry.activePromptId())
                    .isEqualTo(PromptsRegistry.DEFAULT_PROMPT_ID);
            assertThat(registry.promptName(PromptsRegistry.DEFAULT_PROMPT_ID))
                    .isEqualTo("Default");
            assertThat(registry.promptText(PromptsRegistry.DEFAULT_PROMPT_ID))
                    .isEqualTo("Legacy system prompt");
            assertThat(registry.activePromptText())
                    .isEqualTo("Legacy system prompt");
        }

        @Test
        @DisplayName("Missing legacy prompt uses the bundled default")
        void missingLegacyPromptUsesBundledDefault() {
            PromptsRegistry registry = registry(userConfig());

            assertThat(registry.promptText(PromptsRegistry.DEFAULT_PROMPT_ID))
                    .isEqualTo(DEFAULT_SYSTEM_PROMPT);
        }

        @Test
        @DisplayName("Migration runs only once; later initialization keeps the prompt list")
        void notRerun() {
            UserConfig userConfig = userConfig();
            PromptsRegistry first = registry(userConfig);

            String id = first.addPrompt("Custom", "Custom prompt");

            PromptsRegistry second = registry(userConfig());

            assertThat(second.promptIds())
                    .containsExactly(PromptsRegistry.DEFAULT_PROMPT_ID, id);
            assertThat(second.promptName(id)).isEqualTo("Custom");
            assertThat(second.promptText(id)).isEqualTo("Custom prompt");
        }
    }

    @Nested
    @DisplayName("Self-heal")
    class SelfHeal {

        @Test
        @DisplayName("Missing 'default' is restored at the head of the list")
        void missingDefaultRestored() {
            UserConfig userConfig = userConfig();
            userConfig.setPromptsList("prm-aaaa,prm-bbbb");
            userConfig.setActivePrompt("prm-aaaa");

            PromptsRegistry registry = registry(userConfig);

            assertThat(registry.promptIds())
                    .containsExactly(
                            PromptsRegistry.DEFAULT_PROMPT_ID,
                            "prm-aaaa",
                            "prm-bbbb");
            assertThat(registry.activePromptId()).isEqualTo("prm-aaaa");
            assertThat(registry.promptText(PromptsRegistry.DEFAULT_PROMPT_ID))
                    .isEqualTo(DEFAULT_SYSTEM_PROMPT);
        }

        @Test
        @DisplayName("Active pointing at a missing id falls back to 'default'")
        void brokenActiveReset() {
            UserConfig userConfig = userConfig();
            userConfig.setPromptsList(PromptsRegistry.DEFAULT_PROMPT_ID);
            userConfig.setActivePrompt("prm-gone");

            PromptsRegistry registry = registry(userConfig);

            assertThat(registry.activePromptId())
                    .isEqualTo(PromptsRegistry.DEFAULT_PROMPT_ID);
        }

        @Test
        @DisplayName("Missing active prompt falls back to 'default'")
        void missingActiveReset() {
            UserConfig userConfig = userConfig();
            userConfig.setPromptsList(PromptsRegistry.DEFAULT_PROMPT_ID);

            PromptsRegistry registry = registry(userConfig);

            assertThat(registry.activePromptId())
                    .isEqualTo(PromptsRegistry.DEFAULT_PROMPT_ID);
        }

        @Test
        @DisplayName("Missing 'default' fields are filled from bundled defaults")
        void missingDefaultFieldsFilled() {
            UserConfig userConfig = userConfig();
            userConfig.setPromptsList(PromptsRegistry.DEFAULT_PROMPT_ID);

            PromptsRegistry registry = registry(userConfig);

            assertThat(registry.promptName(PromptsRegistry.DEFAULT_PROMPT_ID))
                    .isEqualTo("Default");
            assertThat(registry.promptText(PromptsRegistry.DEFAULT_PROMPT_ID))
                    .isEqualTo(DEFAULT_SYSTEM_PROMPT);
        }
    }

    @Nested
    @DisplayName("addPrompt")
    class AddPrompt {

        @Test
        @DisplayName("New id has 'prm-' prefix and does not collide")
        void generatesId() {
            PromptsRegistry registry = registry(userConfig());

            String id = registry.addPrompt("Custom", "Custom prompt");

            assertThat(id).startsWith("prm-");
            assertThat(registry.promptIds()).contains(id);
            assertThat(registry.promptIds()).hasSize(2);
        }

        @Test
        @DisplayName("Prompt fields are persisted")
        void persistsFields() {
            PromptsRegistry registry = registry(userConfig());

            String id = registry.addPrompt("Custom", "Custom prompt");

            assertThat(registry.promptName(id)).isEqualTo("Custom");
            assertThat(registry.promptText(id)).isEqualTo("Custom prompt");
        }

        @Test
        @DisplayName("Adding a prompt does not change the active one")
        void activeUnchanged() {
            PromptsRegistry registry = registry(userConfig());

            String activeId = registry.activePromptId();
            registry.addPrompt("Custom", "Custom prompt");

            assertThat(registry.activePromptId()).isEqualTo(activeId);
        }

        @Test
        @DisplayName("100 prompts produce 100 unique ids")
        void uniqueIds() {
            PromptsRegistry registry = registry(userConfig());
            Set<String> ids = new HashSet<>();
            ids.add(PromptsRegistry.DEFAULT_PROMPT_ID);

            for (int i = 0; i < 100; i++) {
                String id = registry.addPrompt("Prompt " + i, "Text " + i);

                assertThat(ids.add(id))
                        .as("duplicate id generated: " + id)
                        .isTrue();
            }

            assertThat(registry.promptIds()).hasSize(101);
            assertThat(ids).hasSize(101);
        }
    }

    @Nested
    @DisplayName("updatePrompt")
    class UpdatePrompt {

        @Test
        @DisplayName("Updates prompt name and text")
        void updatesFields() {
            PromptsRegistry registry = registry(userConfig());
            String id = registry.addPrompt("Original", "Original text");

            registry.updatePrompt(id, "Updated", "Updated text");

            assertThat(registry.promptName(id)).isEqualTo("Updated");
            assertThat(registry.promptText(id)).isEqualTo("Updated text");
        }

        @Test
        @DisplayName("Unknown id is a no-op")
        void unknownId() {
            PromptsRegistry registry = registry(userConfig());

            registry.updatePrompt("prm-nope", "Name", "Text");

            assertThat(registry.promptIds())
                    .containsExactly(PromptsRegistry.DEFAULT_PROMPT_ID);
        }

        @Test
        @DisplayName("Blank id is a no-op")
        void blankId() {
            PromptsRegistry registry = registry(userConfig());

            registry.updatePrompt(" ", "Name", "Text");

            assertThat(registry.promptIds())
                    .containsExactly(PromptsRegistry.DEFAULT_PROMPT_ID);
        }
    }

    @Nested
    @DisplayName("removePrompt")
    class RemovePrompt {

        @Test
        @DisplayName("Removes the prompt and its fields")
        void removesPrompt() {
            UserConfig userConfig = userConfig();
            PromptsRegistry registry = registry(userConfig);
            String id = registry.addPrompt("Custom", "Custom prompt");

            registry.removePrompt(id);

            assertThat(registry.promptIds()).doesNotContain(id);
            assertThat(registry.promptName(id)).isEqualTo(id);
            assertThat(registry.promptText(id)).isNull();
        }

        @Test
        @DisplayName("'default' cannot be removed")
        void defaultProtected() {
            PromptsRegistry registry = registry(userConfig());

            registry.removePrompt(PromptsRegistry.DEFAULT_PROMPT_ID);

            assertThat(registry.promptIds())
                    .contains(PromptsRegistry.DEFAULT_PROMPT_ID);
            assertThat(registry.promptText(PromptsRegistry.DEFAULT_PROMPT_ID))
                    .isEqualTo(DEFAULT_SYSTEM_PROMPT);
        }

        @Test
        @DisplayName("Removing the active prompt falls back to 'default'")
        void activeFallback() {
            PromptsRegistry registry = registry(userConfig());
            String id = registry.addPrompt("Custom", "Custom prompt");
            registry.setActivePrompt(id);

            assertThat(registry.activePromptId()).isEqualTo(id);

            registry.removePrompt(id);

            assertThat(registry.activePromptId())
                    .isEqualTo(PromptsRegistry.DEFAULT_PROMPT_ID);
            assertThat(registry.activePromptText())
                    .isEqualTo(DEFAULT_SYSTEM_PROMPT);
        }

        @Test
        @DisplayName("Unknown id is a no-op")
        void unknownId() {
            PromptsRegistry registry = registry(userConfig());

            registry.removePrompt("prm-nope");

            assertThat(registry.promptIds())
                    .containsExactly(PromptsRegistry.DEFAULT_PROMPT_ID);
        }
    }

    @Nested
    @DisplayName("setActivePrompt")
    class SetActivePrompt {

        @Test
        @DisplayName("Switching changes the active prompt")
        void switchPrompt() {
            PromptsRegistry registry = registry(userConfig());
            String id = registry.addPrompt("Custom", "Custom prompt");

            registry.setActivePrompt(id);

            assertThat(registry.activePromptId()).isEqualTo(id);
            assertThat(registry.activePromptText()).isEqualTo("Custom prompt");
        }

        @Test
        @DisplayName("Unknown id is a no-op")
        void unknownId() {
            PromptsRegistry registry = registry(userConfig());

            registry.setActivePrompt("prm-nope");

            assertThat(registry.activePromptId())
                    .isEqualTo(PromptsRegistry.DEFAULT_PROMPT_ID);
        }

        @Test
        @DisplayName("Blank id is a no-op")
        void blankId() {
            PromptsRegistry registry = registry(userConfig());
            String id = registry.addPrompt("Custom", "Custom prompt");
            registry.setActivePrompt(id);

            registry.setActivePrompt(" ");

            assertThat(registry.activePromptId()).isEqualTo(id);
        }
    }

    @Nested
    @DisplayName("promptName")
    class PromptName {

        @Test
        @DisplayName("Unknown id returns the id itself")
        void unknownId() {
            PromptsRegistry registry = registry(userConfig());

            assertThat(registry.promptName("prm-nope")).isEqualTo("prm-nope");
        }

        @Test
        @DisplayName("Configured name wins over id")
        void configuredName() {
            PromptsRegistry registry = registry(userConfig());
            String id = registry.addPrompt("Work", "Work prompt");

            assertThat(registry.promptName(id)).isEqualTo("Work");
        }
    }

    @Nested
    @DisplayName("promptText")
    class PromptText {

        @Test
        @DisplayName("Unknown id returns null")
        void unknownId() {
            PromptsRegistry registry = registry(userConfig());

            assertThat(registry.promptText("prm-nope")).isNull();
        }
    }

    @Nested
    @DisplayName("Prompt order")
    class Order {

        @Test
        @DisplayName("'default' is always first")
        void defaultFirst() {
            PromptsRegistry registry = registry(userConfig());

            registry.addPrompt("A", "Text A");
            registry.addPrompt("B", "Text B");

            assertThat(registry.promptIds().getFirst())
                    .isEqualTo(PromptsRegistry.DEFAULT_PROMPT_ID);
        }

        @Test
        @DisplayName("New prompts are appended in insertion order")
        void insertionOrder() {
            PromptsRegistry registry = registry(userConfig());

            String a = registry.addPrompt("A", "Text A");
            String b = registry.addPrompt("B", "Text B");

            assertThat(registry.promptIds())
                    .containsExactly(PromptsRegistry.DEFAULT_PROMPT_ID, a, b);
        }
    }

    @Nested
    @DisplayName("Idempotent initialization")
    class Idempotent {

        @Test
        @DisplayName("Second initialization does not reset existing prompt data")
        void secondInitDoesNotResetDefault() {
            UserConfig firstConfig = userConfig();
            PromptsRegistry first = registry(firstConfig);

            first.updatePrompt(
                    PromptsRegistry.DEFAULT_PROMPT_ID,
                    "My default",
                    "My custom default text");

            PromptsRegistry second = registry(userConfig());

            assertThat(second.promptIds())
                    .containsExactly(PromptsRegistry.DEFAULT_PROMPT_ID);
            assertThat(second.promptName(PromptsRegistry.DEFAULT_PROMPT_ID))
                    .isEqualTo("My default");
            assertThat(second.promptText(PromptsRegistry.DEFAULT_PROMPT_ID))
                    .isEqualTo("My custom default text");
        }
    }
}
