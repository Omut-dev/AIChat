package omut.aichat.config;

import java.util.List;

/**
 * Facade over bundled defaults ({@link AppDefaults}) and persisted
 * user overrides ({@link UserConfig}).
 * <p>
 * Every getter resolves "user value if present, otherwise default"
 * — that combination lives here, and only here. Anything that only
 * reads {@code application.properties} belongs to AppDefaults;
 * anything that only persists user settings belongs to UserConfig.
 * <p>
 * Two collections are modeled on top of the flat storage, both with
 * the same shape — a list of named items, one active, and one item
 * that cannot be removed:
 * <ul>
 *   <li><b>Named Ollama servers (F18).</b> {@link #getBaseUrl()} is
 *       derived from the active server. The {@code local} server is
 *       mandatory: re-created from the legacy {@code ollama.base.url}
 *       on first run, restored on startup if missing, and never
 *       removable. The persisted {@code active.server} falls back to
 *       {@code local} when it points at a removed id.</li>
 *   <li><b>Named prompts (F13).</b> {@link #systemPrompt()} returns
 *       the active prompt's text. The {@code default} prompt is
 *       mandatory: re-created from the legacy {@code chat.system.prompt}
 *       on first run, restored on startup if missing, and never
 *       removable. The persisted {@code active.prompt} falls back to
 *       {@code default} when it points at a removed id.</li>
 * </ul>
 */
public class AppConfig {

    public static final String LOCAL_SERVER_ID = "local";
    public static final String DEFAULT_PROMPT_ID = "default";

    private final AppDefaults defaults = new AppDefaults();
    private final UserConfig userConfig;
    private final PromptsRegistry promptsRegistry;
    private final ServersRegistry serversRegistry;

    private volatile String baseUrl;
    private volatile String systemPrompt;
    private volatile double temperature;
    private volatile int requestTimeoutMinutes;
    private volatile int historyMaxMessages;
    private volatile int attachMaxChars;

    public AppConfig() {
        this(new UserConfig());
    }

    AppConfig(UserConfig userConfig) {
        this.userConfig = userConfig;
        this.serversRegistry = new ServersRegistry(userConfig, defaults.baseUrl());
        this.promptsRegistry = new PromptsRegistry(userConfig, defaults.systemPrompt());
        this.baseUrl = serversRegistry.resolvedBaseUrl();
        this.systemPrompt = promptsRegistry.activePromptText();
        this.temperature = resolveTemperature();
        this.requestTimeoutMinutes = resolveRequestTimeoutMinutes();
        this.historyMaxMessages = resolveHistoryMaxMessages();
        this.attachMaxChars = resolveAttachMaxChars();
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * Sets the URL of the active server, parsing protocol, host
     * and port out of it. Other servers are untouched. Blank input
     * resets the active server's host/port to the bundled defaults.
     */
    public void setBaseUrl(String baseUrl) {
        serversRegistry.setActiveServerBaseUrl(baseUrl);
        this.baseUrl = serversRegistry.resolvedBaseUrl();
    }

    public String defaultBaseUrl() {
        return defaults.baseUrl();
    }

    /** Ids of all configured servers, in display order. Never empty. */
    public List<String> serverIds() {
        return serversRegistry.serverIds();
    }

    /** ID of the currently active server. Never {@code null}. */
    public String activeServerId() {
        return serversRegistry.activeServerId();
    }

    /** Display name of the server with the given id, or the id itself. */
    public String serverName(String serverId) {
        return serversRegistry.serverName(serverId);
    }

    /**
     * Derived URL of the given server, or {@code null} if the server
     * has no host set.
     */
    public String serverBaseUrl(String serverId) {
        return serversRegistry.serverBaseUrl(serverId);
    }

    /**
     * Adds a new server with a generated id and returns that id. The
     * server is appended to the list; the active one is unchanged.
     */
    public String addServer(String name, String host, int port) {
        return serversRegistry.addServer(name, host, port);
    }

    public void updateServer(String serverId, String name, String host, int port) {
        serversRegistry.updateServer(serverId, name, host, port);
        if (serverId != null && serverId.equals(activeServerId())) {
            this.baseUrl = serversRegistry.resolvedBaseUrl();
        }
    }

    /**
     * Removes a non-local server. The {@code local} server cannot be
     * removed. If the removed server was active, the active id falls
     * back to {@code local}.
     */
    public void removeServer(String serverId) {
        serversRegistry.removeServer(serverId);
        this.baseUrl = serversRegistry.resolvedBaseUrl();
    }

    /** Switches the active server. Unknown ids are ignored. */
    public void setActiveServer(String serverId) {
        serversRegistry.setActiveServer(serverId);
        this.baseUrl = serversRegistry.resolvedBaseUrl();
    }

    /** Ids of all configured prompts, in display order. Never empty. */
    public List<String> promptIds() {
        return promptsRegistry.promptIds();
    }

    /** ID of the currently active prompt. Never {@code null}. */
    public String activePromptId() {
        return promptsRegistry.activePromptId();
    }

    /** Display name of the prompt with the given id, or the id itself. */
    public String promptName(String promptId) {
        return promptsRegistry.promptName(promptId);
    }

    /** Full text of the given prompt, or {@code null} if not set. */
    public String promptText(String promptId) {
        return promptsRegistry.promptText(promptId);
    }

    /**
     * Adds a new prompt with a generated id and returns that id.
     * The prompt is appended to the list; the active one is unchanged.
     */
    public String addPrompt(String name, String text) {
        return promptsRegistry.addPrompt(name, text);
    }

    public void updatePrompt(String promptId, String name, String text) {
        promptsRegistry.updatePrompt(promptId, name, text);
        if (promptId != null && promptId.equals(activePromptId())) {
            this.systemPrompt = promptsRegistry.activePromptText();
        }
    }

    /**
     * Removes a non-default prompt. The {@code default} prompt cannot
     * be removed. If the removed prompt was active, the active id falls
     * back to {@code default}.
     */
    public void removePrompt(String promptId) {
        promptsRegistry.removePrompt(promptId);
        this.systemPrompt = promptsRegistry.activePromptText();
    }

    /** Switches the active prompt. Unknown ids are ignored. */
    public void setActivePrompt(String promptId) {
        promptsRegistry.setActivePrompt(promptId);
        this.systemPrompt = promptsRegistry.activePromptText();
    }

    public int requestTimeoutMinutes() {
        return requestTimeoutMinutes;
    }

    public double temperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
        userConfig.setTemperature(temperature);
    }

    public double defaultTemperature() {
        return defaults.temperature();
    }

    public void setRequestTimeoutMinutes(int minutes) {
        this.requestTimeoutMinutes = minutes;
        userConfig.setRequestTimeoutMinutes(minutes);
    }

    public int defaultRequestTimeoutMinutes() {
        return defaults.requestTimeoutMinutes();
    }

    public String systemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String prompt) {
        updatePrompt(activePromptId(), promptName(activePromptId()), prompt);
    }

    public String defaultSystemPrompt() {
        return defaults.systemPrompt();
    }

    public String selectedModel() {
        String userValue = userConfig.getSelectedModel();
        if (userValue != null && !userValue.isBlank()) {
            return userValue;
        }
        return defaults.model();
    }

    public void setSelectedModel(String model) {
        if (model == null || model.isBlank()) {
            userConfig.setSelectedModel(null);
        } else {
            userConfig.setSelectedModel(model);
        }
    }

    public String theme() {
        String userValue = userConfig.getTheme();
        if (userValue != null && !userValue.isBlank()) {
            return userValue;
        }
        return defaults.theme();
    }

    public void setTheme(String theme) {
        if (theme == null || theme.isBlank()) {
            userConfig.setTheme(null);
        } else {
            userConfig.setTheme(theme);
        }
    }

    public int historyMaxMessages() {
        return historyMaxMessages;
    }

    public void setHistoryMaxMessages(int max) {
        this.historyMaxMessages = max;
        userConfig.setHistoryMaxMessages(max);
    }

    public int defaultHistoryMaxMessages() {
        return defaults.historyMaxMessages();
    }

    public int attachMaxChars() {
        return attachMaxChars;
    }

    public void setAttachMaxChars(int max) {
        this.attachMaxChars = max;
        userConfig.setAttachMaxChars(max);
    }

    public int defaultAttachMaxChars() {
        return defaults.attachMaxChars();
    }

    public String windowTitle() {
        return defaults.windowTitle();
    }

    public int windowWidth() {
        return defaults.windowWidth();
    }

    public int windowHeight() {
        return defaults.windowHeight();
    }

    public String highlightJsPath() {
        return defaults.highlightJsPath();
    }

    public String chatTemplatePath() {
        return defaults.chatTemplatePath();
    }

    public String applicationCssPath() {
        return defaults.applicationCssPath();
    }

    public String chatCssPath() {
        return defaults.chatCssPath();
    }

    public String chatJsPath() {
        return defaults.chatJsPath();
    }

    private double resolveTemperature() {
        Double userValue = userConfig.getTemperature();
        if (userValue != null) return userValue;
        return defaults.temperature();
    }

    private int resolveRequestTimeoutMinutes() {
        Integer userValue = userConfig.getRequestTimeoutMinutes();
        if (userValue != null) return userValue;
        return defaults.requestTimeoutMinutes();
    }

    private int resolveHistoryMaxMessages() {
        Integer userValue = userConfig.getHistoryMaxMessages();
        if (userValue != null) return userValue;
        return defaults.historyMaxMessages();
    }

    private int resolveAttachMaxChars() {
        Integer userValue = userConfig.getAttachMaxChars();
        if (userValue != null) return userValue;
        return defaults.attachMaxChars();
    }
}