package omut.aichat.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Manages named prompts stored in {@link UserConfig}.
 * <p>
 * The {@code default} prompt is mandatory: it is created from the legacy
 * {@code chat.system.prompt} on first run, restored on startup if missing,
 * and never removable. The persisted {@code active.prompt} falls back
 * to {@code default} when it points at a removed or unknown id.
 */
final class PromptsRegistry {

    static final String DEFAULT_PROMPT_ID = "default";

    private static final Logger log = LoggerFactory.getLogger(PromptsRegistry.class);

    private final UserConfig userConfig;
    private final String defaultSystemPrompt;

    PromptsRegistry(UserConfig userConfig, String defaultSystemPrompt) {
        this.userConfig = userConfig;
        this.defaultSystemPrompt = defaultSystemPrompt;

        migrateLegacySystemPromptIfNeeded();
        ensureDefaultPrompt();
    }

    /** Ids of all configured prompts, in display order. Never empty. */
    public List<String> promptIds() {
        return UserConfig.splitCsv(userConfig.getPromptsList());
    }

    /** ID of the currently active prompt. Never {@code null}. */
    public String activePromptId() {
        String id = userConfig.getActivePrompt();
        if (id == null || id.isBlank() || !promptIds().contains(id)) {
            userConfig.setActivePrompt(DEFAULT_PROMPT_ID);
            return DEFAULT_PROMPT_ID;
        }
        return id;
    }

    /** Display name of the prompt with the given id, or the id itself. */
    public String promptName(String promptId) {
        String name = userConfig.getPromptField(promptId, ConfigKeys.PROMPT_NAME_SUFFIX);
        return (name == null || name.isBlank()) ? promptId : name;
    }

    /** Full text of the given prompt, or {@code null} if not set. */
    public String promptText(String promptId) {
        return userConfig.getPromptField(promptId, ConfigKeys.PROMPT_TEXT_SUFFIX);
    }

    /**
     * Adds a new prompt with a generated id and returns that id.
     * The prompt is appended to the list; the active one is unchanged.
     */
    public String addPrompt(String name, String text) {
        String id = generateId("prm", promptIds());
        userConfig.setPromptField(id, ConfigKeys.PROMPT_NAME_SUFFIX, name);
        userConfig.setPromptField(id, ConfigKeys.PROMPT_TEXT_SUFFIX, text);

        List<String> ids = new ArrayList<>(promptIds());
        ids.add(id);
        userConfig.setPromptsList(String.join(",", ids));
        return id;
    }

    public void updatePrompt(String promptId, String name, String text) {
        if (promptId == null || promptId.isBlank()) return;
        if (!promptIds().contains(promptId)) return;

        userConfig.setPromptField(promptId, ConfigKeys.PROMPT_NAME_SUFFIX, name);
        userConfig.setPromptField(promptId, ConfigKeys.PROMPT_TEXT_SUFFIX, text);
    }

    /**
     * Removes a non-default prompt. The {@code default} prompt cannot
     * be removed. If the removed prompt was active, the active id falls
     * back to {@code default}.
     */
    public void removePrompt(String promptId) {
        if (promptId == null || promptId.isBlank()) return;
        if (DEFAULT_PROMPT_ID.equals(promptId)) return;
        if (!promptIds().contains(promptId)) return;

        userConfig.removePrompt(promptId);
    }

    /** Switches the active prompt. Unknown ids are ignored. */
    public void setActivePrompt(String promptId) {
        if (promptId == null || promptId.isBlank()) return;
        if (!promptIds().contains(promptId)) return;

        userConfig.setActivePrompt(promptId);
    }

    /**
     * Returns the text of the active prompt, falling back to the bundled
     * default if the stored text is missing.
     */
    String activePromptText() {
        String text = promptText(activePromptId());
        return text != null ? text : defaultSystemPrompt;
    }

    private void migrateLegacySystemPromptIfNeeded() {
        String existingList = userConfig.getPromptsList();
        if (existingList != null && !existingList.isBlank()) {
            return;
        }

        String legacy = userConfig.getSystemPrompt();
        String text = (legacy != null && !legacy.isBlank())
                ? legacy
                : defaultSystemPrompt;

        userConfig.setPromptField(
                DEFAULT_PROMPT_ID, ConfigKeys.PROMPT_NAME_SUFFIX, "Default");
        userConfig.setPromptField(
                DEFAULT_PROMPT_ID, ConfigKeys.PROMPT_TEXT_SUFFIX, text);

        userConfig.setPromptsList(DEFAULT_PROMPT_ID);
        userConfig.setActivePrompt(DEFAULT_PROMPT_ID);
        log.info("Migrated legacy system prompt to named prompt '{}'",
                DEFAULT_PROMPT_ID);
    }

    private void ensureDefaultPrompt() {
        List<String> ids = new ArrayList<>(promptIds());

        if (!ids.contains(DEFAULT_PROMPT_ID)) {
            ids.addFirst(DEFAULT_PROMPT_ID);
            userConfig.setPromptsList(String.join(",", ids));
            log.info("Self-heal: restored missing '{}' prompt entry",
                    DEFAULT_PROMPT_ID);
        }

        if (userConfig.getPromptField(
                DEFAULT_PROMPT_ID, ConfigKeys.PROMPT_NAME_SUFFIX) == null) {
            userConfig.setPromptField(
                    DEFAULT_PROMPT_ID, ConfigKeys.PROMPT_NAME_SUFFIX, "Default");
        }

        if (userConfig.getPromptField(
                DEFAULT_PROMPT_ID, ConfigKeys.PROMPT_TEXT_SUFFIX) == null) {
            userConfig.setPromptField(
                    DEFAULT_PROMPT_ID, ConfigKeys.PROMPT_TEXT_SUFFIX, defaultSystemPrompt);
        }

        String active = userConfig.getActivePrompt();
        if (active == null || active.isBlank() || !ids.contains(active)) {
            userConfig.setActivePrompt(DEFAULT_PROMPT_ID);
        }
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
