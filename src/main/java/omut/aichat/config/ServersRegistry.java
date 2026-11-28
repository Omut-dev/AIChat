package omut.aichat.config;

import omut.aichat.utils.UrlParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Registry of named Ollama servers persisted in {@link UserConfig}.
 *
 * <p>The local server is mandatory: it is created from the legacy
 * {@code ollama.base.url} on first run, restored on startup if missing,
 * and never removable. The active server falls back to local when its
 * persisted id is missing or invalid.
 */
final class ServersRegistry {

    private static final Logger log = LoggerFactory.getLogger(ServersRegistry.class);

    private static final String DEFAULT_PROTOCOL = "http";

    private final UserConfig userConfig;
    private final String defaultBaseUrl;

    ServersRegistry(UserConfig userConfig, String defaultBaseUrl) {
        this.userConfig = userConfig;
        this.defaultBaseUrl = defaultBaseUrl;

        migrateLegacyBaseUrlIfNeeded();
        ensureLocalServer();
    }

    /**
     * Ids of all configured servers, in display order. Never empty.
     */
    List<String> serverIds() {
        return UserConfig.splitCsv(userConfig.getServerList());
    }

    /**
     * ID of the currently active server. Never {@code null}.
     */
    String activeServerId() {
        String id = userConfig.getActiveServer();
        if (id == null || id.isBlank() || !serverIds().contains(id)) {
            userConfig.setActiveServer(AppConfig.LOCAL_SERVER_ID);
            return AppConfig.LOCAL_SERVER_ID;
        }
        return id;
    }

    /**
     * Display name of the server with the given id, or the id itself.
     */
    String serverName(String serverId) {
        String name = userConfig.getServerField(
                serverId, ConfigKeys.SERVER_NAME_SUFFIX);
        return (name == null || name.isBlank()) ? serverId : name;
    }

    /**
     * Derived URL of the given server, or {@code null} if the server
     * has no host set.
     */
    String serverBaseUrl(String serverId) {
        return buildServerUrl(serverId);
    }

    /**
     * Convenience: derived URL of the active server.
     */
    String activeServerBaseUrl() {
        return serverBaseUrl(activeServerId());
    }

    /**
     * Returns the active server URL, falling back to the bundled default.
     */
    String resolvedBaseUrl() {
        String url = activeServerBaseUrl();
        return url != null ? url : defaultBaseUrl;
    }

    /**
     * Sets the URL of the active server, parsing protocol, host and port
     * out of it. Blank input resets the active server to bundled defaults.
     */
    void setActiveServerBaseUrl(String baseUrl) {
        String normalized = UrlParser.normalize(baseUrl);
        if (normalized == null) {
            resetActiveServerToDefaults();
        } else {
            parseAndStoreActiveServer(normalized);
        }
    }

    /**
     * Adds a new server with a generated id and returns that id. The
     * server is appended to the list; the active one is unchanged.
     */
    String addServer(String name, String host, int port) {
        String id = generateId("srv", serverIds());
        userConfig.setServerField(id, ConfigKeys.SERVER_NAME_SUFFIX, name);
        userConfig.setServerField(id, ConfigKeys.SERVER_PROTOCOL_SUFFIX, DEFAULT_PROTOCOL);
        userConfig.setServerField(id, ConfigKeys.SERVER_HOST_SUFFIX, host);
        userConfig.setServerField(id, ConfigKeys.SERVER_PORT_SUFFIX, Integer.toString(port));

        List<String> ids = new ArrayList<>(serverIds());
        ids.add(id);
        userConfig.setServerList(String.join(",", ids));
        return id;
    }

    void updateServer(String serverId, String name, String host, int port) {
        if (serverId == null || serverId.isBlank()) return;
        if (!serverIds().contains(serverId)) return;

        userConfig.setServerField(serverId, ConfigKeys.SERVER_NAME_SUFFIX, name);
        userConfig.setServerField(serverId, ConfigKeys.SERVER_HOST_SUFFIX, host);
        userConfig.setServerField(serverId, ConfigKeys.SERVER_PORT_SUFFIX, Integer.toString(port));
    }

    /**
     * Removes a non-local server. The local server cannot be removed.
     * If the removed server was active, the active id falls back to local.
     */
    void removeServer(String serverId) {
        if (serverId == null || serverId.isBlank()) return;
        if (AppConfig.LOCAL_SERVER_ID.equals(serverId)) return;
        if (!serverIds().contains(serverId)) return;

        userConfig.removeServer(serverId);
    }

    /**
     * Switches the active server. Unknown ids are ignored.
     */
    void setActiveServer(String serverId) {
        if (serverId == null || serverId.isBlank()) return;
        if (!serverIds().contains(serverId)) return;

        userConfig.setActiveServer(serverId);
    }

    private void migrateLegacyBaseUrlIfNeeded() {
        String existingList = userConfig.getServerList();
        if (existingList != null && !existingList.isBlank()) {
            return;
        }

        String legacy = userConfig.getBaseUrl();
        String source = (legacy != null && !legacy.isBlank())
                ? legacy
                : defaultBaseUrl;

        UrlParser.Parsed parsed = UrlParser.parse(source);
        if (parsed == null) {
            parsed = UrlParser.parse(defaultBaseUrl);
        }
        if (parsed == null) {
            log.warn("Cannot parse bundled default base URL: {}", defaultBaseUrl);
            parsed = new UrlParser.Parsed(
                    UrlParser.DEFAULT_PROTOCOL, "127.0.0.1", UrlParser.DEFAULT_PORT);
        }

        userConfig.setServerField(
                AppConfig.LOCAL_SERVER_ID, ConfigKeys.SERVER_NAME_SUFFIX, "Local");
        userConfig.setServerField(
                AppConfig.LOCAL_SERVER_ID, ConfigKeys.SERVER_PROTOCOL_SUFFIX, parsed.protocol());
        userConfig.setServerField(
                AppConfig.LOCAL_SERVER_ID, ConfigKeys.SERVER_HOST_SUFFIX, parsed.host());
        userConfig.setServerField(
                AppConfig.LOCAL_SERVER_ID, ConfigKeys.SERVER_PORT_SUFFIX,
                Integer.toString(parsed.port()));

        userConfig.setServerList(AppConfig.LOCAL_SERVER_ID);
        userConfig.setActiveServer(AppConfig.LOCAL_SERVER_ID);
        log.info("Migrated legacy base URL to named server '{}': {}",
                AppConfig.LOCAL_SERVER_ID, source);
    }

    private void ensureLocalServer() {
        List<String> ids = new ArrayList<>(serverIds());

        if (!ids.contains(AppConfig.LOCAL_SERVER_ID)) {
            ids.addFirst(AppConfig.LOCAL_SERVER_ID);
            userConfig.setServerList(String.join(",", ids));
            log.info("Self-heal: restored missing '{}' server entry",
                    AppConfig.LOCAL_SERVER_ID);
        }

        if (userConfig.getServerField(
                AppConfig.LOCAL_SERVER_ID, ConfigKeys.SERVER_NAME_SUFFIX) == null) {
            userConfig.setServerField(
                    AppConfig.LOCAL_SERVER_ID, ConfigKeys.SERVER_NAME_SUFFIX, "Local");
        }
        if (userConfig.getServerField(
                AppConfig.LOCAL_SERVER_ID, ConfigKeys.SERVER_PROTOCOL_SUFFIX) == null) {
            userConfig.setServerField(
                    AppConfig.LOCAL_SERVER_ID, ConfigKeys.SERVER_PROTOCOL_SUFFIX, DEFAULT_PROTOCOL);
        }
        if (userConfig.getServerField(
                AppConfig.LOCAL_SERVER_ID, ConfigKeys.SERVER_HOST_SUFFIX) == null) {
            UrlParser.Parsed defaults = UrlParser.parse(defaultBaseUrl);
            String host = (defaults != null) ? defaults.host() : "127.0.0.1";
            userConfig.setServerField(
                    AppConfig.LOCAL_SERVER_ID, ConfigKeys.SERVER_HOST_SUFFIX, host);
        }
        if (userConfig.getServerField(
                AppConfig.LOCAL_SERVER_ID, ConfigKeys.SERVER_PORT_SUFFIX) == null) {
            UrlParser.Parsed defaults = UrlParser.parse(defaultBaseUrl);
            int port = (defaults != null) ? defaults.port() : UrlParser.DEFAULT_PORT;
            userConfig.setServerField(
                    AppConfig.LOCAL_SERVER_ID, ConfigKeys.SERVER_PORT_SUFFIX,
                    Integer.toString(port));
        }

        String active = userConfig.getActiveServer();
        if (active == null || active.isBlank() || !ids.contains(active)) {
            userConfig.setActiveServer(AppConfig.LOCAL_SERVER_ID);
        }
    }

    private String buildServerUrl(String serverId) {
        String protocol = userConfig.getServerField(
                serverId, ConfigKeys.SERVER_PROTOCOL_SUFFIX);
        String host = userConfig.getServerField(
                serverId, ConfigKeys.SERVER_HOST_SUFFIX);
        String port = userConfig.getServerField(
                serverId, ConfigKeys.SERVER_PORT_SUFFIX);

        if (host == null || host.isBlank()) return null;
        if (protocol == null || protocol.isBlank()) protocol = DEFAULT_PROTOCOL;
        if (port == null || port.isBlank()) port = String.valueOf(UrlParser.DEFAULT_PORT);

        return protocol + "://" + host + ":" + port;
    }

    private void parseAndStoreActiveServer(String url) {
        UrlParser.Parsed parsed = UrlParser.parse(url);
        if (parsed == null) {
            log.warn("Cannot parse URL for active server: {}", url);
            return;
        }

        String id = activeServerId();
        userConfig.setServerField(
                id, ConfigKeys.SERVER_PROTOCOL_SUFFIX, parsed.protocol());
        userConfig.setServerField(
                id, ConfigKeys.SERVER_HOST_SUFFIX, parsed.host());
        userConfig.setServerField(
                id, ConfigKeys.SERVER_PORT_SUFFIX, Integer.toString(parsed.port()));
    }

    private void resetActiveServerToDefaults() {
        UrlParser.Parsed parsed = UrlParser.parse(defaultBaseUrl);
        if (parsed == null) return;

        String id = activeServerId();
        userConfig.setServerField(
                id, ConfigKeys.SERVER_PROTOCOL_SUFFIX, parsed.protocol());
        userConfig.setServerField(
                id, ConfigKeys.SERVER_HOST_SUFFIX, parsed.host());
        userConfig.setServerField(
                id, ConfigKeys.SERVER_PORT_SUFFIX, Integer.toString(parsed.port()));
    }

    private static String generateId(String prefix, List<String> existing) {
        for (int attempt = 0; attempt < 16; attempt++) {
            String candidate = prefix + "-"
                    + UUID.randomUUID().toString().substring(0, 8);
            if (!existing.contains(candidate)) return candidate;
        }
        throw new IllegalStateException(
                "Could not allocate a unique id with prefix '" + prefix + "'");
    }
}