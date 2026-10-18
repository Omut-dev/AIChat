package omut.aichat.chat;

import omut.aichat.config.AppConfig;
import omut.aichat.config.AppSettings;
import omut.aichat.service.LlmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

public class ChatSession implements ChatCommands {

    private static final Logger log = LoggerFactory.getLogger(ChatSession.class);

    private final AtomicReference<Future<?>> currentRequest = new AtomicReference<>();
    private final AtomicReference<Object> requestToken = new AtomicReference<>();
    private final LlmService llmService;
    private final AppConfig config;
    private final ChatDispatcher dispatcher = new ChatDispatcher();
    private final HistoryEditor editor = new HistoryEditor();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            r -> new Thread(r, "chat-worker"));

    public ChatSession(LlmService llmService, AppConfig config) {
        this.llmService = llmService;
        this.config = config;
        String prompt = config.systemPrompt();
        if (!prompt.isBlank()) {
            editor.append(AIChatMessage.system(prompt));
        }
    }

    public void addListener(ChatListener listener) {
        dispatcher.add(listener);
    }

    public List<AIChatMessage> getHistory() {
        return editor.snapshot();
    }

    public void checkAvailability() {
        submit(() -> dispatcher.status(llmService.isAvailable()));
    }

    public void send(String userText) {
        if (userText == null || userText.isBlank()) return;
        if (executor.isShutdown()) return;
        if (requestToken.get() != null) return;

        AIChatMessage userMessage = AIChatMessage.user(userText.trim());

        Object token = new Object();
        requestToken.set(token);

        editor.append(userMessage);
        dispatcher.message(userMessage);
        dispatcher.thinkingStarted();

        submitStreamingRequest(token);
    }

    private void submitStreamingRequest(Object token) {
        try {
            Future<?> future = executor.submit(() -> {
                long start = System.nanoTime();
                try {
                    List<AIChatMessage> requestHistory =
                            RequestHistoryBuilder.trim(editor.snapshot(), config.historyMaxMessages());
                    llmService.askStreaming(requestHistory, new StreamingCallback() {

                        @Override
                        public void onToken(String chunk, String fullText) {
                            if (requestToken.get() != token) return;
                            dispatcher.token(chunk, fullText);
                        }

                        @Override
                        public void onComplete(LlmResponse response) {
                            if (requestToken.get() != token) return;
                            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
                            AIChatMessage assistantMessage = AIChatMessage.assistant(
                                    response.text(), response.durationMillis(), currentModel());
                            editor.append(assistantMessage);
                            dispatcher.message(assistantMessage);
                            dispatcher.responseTime(elapsedMillis);
                            dispatcher.tokensPerSecond(response.tokensPerSecond());
                        }

                        @Override
                        public void onError(Throwable error) {
                            if (requestToken.get() != token) return;
                            AIChatMessage errorMsg = AIChatMessage.system(ErrorMessages.humanize(error));
                            editor.append(errorMsg);
                            dispatcher.message(errorMsg);
                        }
                    });

                } catch (Exception e) {
                    if (requestToken.get() != token) return;
                    AIChatMessage error = AIChatMessage.system(ErrorMessages.humanize(e));
                    editor.append(error);
                    dispatcher.message(error);
                } finally {
                    if (requestToken.compareAndSet(token, null)) {
                        currentRequest.set(null);
                        dispatcher.thinkingFinished();
                    }
                }
            });
            currentRequest.set(future);
        } catch (RejectedExecutionException e) {
            requestToken.set(null);
            dispatcher.thinkingFinished();
        }
    }

    public void regenerateLast() {
        if (executor.isShutdown()) return;
        if (requestToken.get() != null) return;

        int lastUserIndex = editor.lastUserIndex();

        if (lastUserIndex < 0) {
            dispatcher.notice("Nothing to regenerate.", NoticeLevel.INFO);
            return;
        }

        editor.cutAfter(lastUserIndex);
        dispatcher.historyChanged();

        Object token = new Object();
        requestToken.set(token);
        dispatcher.thinkingStarted();
        submitStreamingRequest(token);
    }

    @Override
    public void deleteFrom(String messageId) {
        if (messageId == null || messageId.isBlank()) return;
        if (executor.isShutdown()) return;
        if (requestToken.get() != null) {
            dispatcher.notice("Cannot delete while generating.", NoticeLevel.INFO);
            return;
        }

        int idx = editor.indexOf(messageId);
        if (idx < 0) return;
        if (editor.get(idx).role() == AIChatMessage.Role.SYSTEM) return;

        editor.cutFrom(idx);
        dispatcher.historyChanged();
    }

    @Override
    public void editUserMessage(String messageId, String newText) {
        if (messageId == null || messageId.isBlank()) return;
        if (newText == null || newText.isBlank()) return;
        if (executor.isShutdown()) return;
        if (requestToken.get() != null) {
            dispatcher.notice("Cannot edit while generating.", NoticeLevel.INFO);
            return;
        }

        int idx = editor.indexOf(messageId);
        if (idx < 0) return;
        if (editor.get(idx).role() != AIChatMessage.Role.USER) return;

        editor.cutFrom(idx);
        AIChatMessage userMessage = AIChatMessage.user(newText.trim());
        editor.append(userMessage);
        dispatcher.message(userMessage);
        dispatcher.historyChanged();

        Object token = new Object();
        requestToken.set(token);
        dispatcher.thinkingStarted();
        submitStreamingRequest(token);
    }

    public void loadModels() {
        submit(() -> {
            List<String> models = llmService.listModels();
            dispatcher.modelsLoaded(models);
        });
    }

    public void switchModel(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            dispatcher.notice("Invalid model name.", NoticeLevel.ERROR);
            return;
        }
        submit(() -> {
            try {
                llmService.switchModel(modelName);
                config.setSelectedModel(modelName);
                dispatcher.modelChanged(modelName);
            } catch (Exception e) {
                dispatcher.notice("Failed to switch model: " + e.getMessage(), NoticeLevel.ERROR);
            }
        });
    }

    public String currentModel() {
        return llmService.currentModel();
    }

    public void attachFile(String fileName, String content) {
        if (fileName == null || fileName.isBlank()) return;
        if (content == null || content.isBlank()) {
            dispatcher.notice("Attached file is empty: " + fileName, NoticeLevel.ERROR);
            return;
        }

        int max = config.attachMaxChars();
        if (content.length() > max) {
            dispatcher.notice(
                    "File too large: " + content.length() + " chars, limit is " + max, NoticeLevel.ERROR);
            return;
        }

        submit(() -> {
            String text = "[Attached: " + fileName + "]\n\n" + content;
            AIChatMessage attachment = AIChatMessage.system(text);
            editor.append(attachment);
            dispatcher.message(attachment);
            dispatcher.notice("Attached: " + fileName, NoticeLevel.SUCCESS);
        });
    }

    public void clear() {
        submit(() -> {
            editor.clear();
            String prompt = config.systemPrompt();
            if (!prompt.isBlank()) {
                editor.append(AIChatMessage.system(prompt));
            }
            dispatcher.cleared();
            dispatcher.notice("Chat cleared.", NoticeLevel.INFO);
        });
    }

    public void shutdown() {
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                log.warn("chat-worker did not terminate within 2 seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void cancelCurrentRequest() {
        Object token = requestToken.get();
        if (token == null) return;

        if (!requestToken.compareAndSet(token, null)) return;

        Future<?> request = currentRequest.getAndSet(null);
        if (request != null && !request.isDone()) {
            request.cancel(true);
        }

        AIChatMessage cancelNote = AIChatMessage.system("Generation cancelled.");
        editor.append(cancelNote);
        dispatcher.message(cancelNote);
        dispatcher.requestCancelled();
        dispatcher.thinkingFinished();
    }

    /**
     * Applies a full settings snapshot atomically on the worker thread:
     * persists each value through {@link AppConfig}, then rebuilds the
     * streaming model so temperature and timeout changes take effect
     * without restart.
     * <p>
     * Base URL is intentionally not part of this path. It belongs to
     * the active named server and is changed through
     * {@link #switchServer(String)} or the Settings → Servers block.
     * <p>
     * Prompt is likewise not part of this path — it belongs to the
     * active named prompt and is changed through
     * {@link #switchPrompt(String)} or the Settings → Prompts block.
     */
    public void applySettings(AppSettings settings) {
        if (settings == null) return;
        submit(() -> {
            try {
                config.setTemperature(settings.temperature());
                config.setRequestTimeoutMinutes(settings.requestTimeoutMinutes());
                config.setHistoryMaxMessages(settings.historyMaxMessages());
                config.setAttachMaxChars(settings.attachMaxChars());
                llmService.rebuildModel();
                dispatcher.notice("Settings saved.", NoticeLevel.SUCCESS);
                dispatcher.status(llmService.isAvailable());
            } catch (Exception e) {
                dispatcher.notice("Failed to apply settings: " + e.getMessage(),
                        NoticeLevel.ERROR);
            }
        });
    }

    /**
     * Switches to another named Ollama server, rebuilds the streaming
     * model against its URL, and re-checks availability. The active
     * server is persisted through {@link AppConfig}.
     */
    public void switchServer(String serverId) {
        if (serverId == null || serverId.isBlank()) return;
        submit(() -> {
            try {
                config.setActiveServer(serverId);
                llmService.rebuildModel();
                dispatcher.notice(
                        "Switched to server: " + config.serverName(serverId),
                        NoticeLevel.SUCCESS);
                dispatcher.status(llmService.isAvailable());
            } catch (Exception e) {
                dispatcher.notice("Failed to switch server: " + e.getMessage(),
                        NoticeLevel.ERROR);
            }
        });
    }

    /**
     * Switches to another named prompt and applies its text to the
     * SYSTEM message in the current transcript. The active prompt is
     * persisted through {@link AppConfig}.
     * <p>
     * Unlike {@link #switchServer(String)} this does not touch the
     * streaming model — the prompt only affects the next request's
     * message list, not the model configuration.
     */
    public void switchPrompt(String promptId) {
        if (promptId == null || promptId.isBlank()) return;
        submit(() -> {
            try {
                config.setActivePrompt(promptId);
                applySystemPromptToHistory(config.systemPrompt());
                dispatcher.historyChanged();
                dispatcher.notice(
                        "Switched to prompt: " + config.promptName(promptId),
                        NoticeLevel.SUCCESS);
            } catch (Exception e) {
                dispatcher.notice("Failed to switch prompt: " + e.getMessage(),
                        NoticeLevel.ERROR);
            }
        });
    }

    /**
     * Updates the active prompt's text and applies it to the SYSTEM
     * message in the current transcript.
     */
    public void setSystemPrompt(String prompt) {
        submit(() -> {
            try {
                config.setSystemPrompt(prompt);
                applySystemPromptToHistory(config.systemPrompt());
                dispatcher.historyChanged();
                dispatcher.notice("System prompt updated.", NoticeLevel.SUCCESS);
            } catch (Exception e) {
                dispatcher.notice("Failed to update system prompt: " + e.getMessage(),
                        NoticeLevel.ERROR);
            }
        });
    }

    private void applySystemPromptToHistory(String text) {
        boolean hasSystem = !editor.isEmpty()
                && editor.first().role() == AIChatMessage.Role.SYSTEM;

        if (text == null || text.isBlank()) {
            if (hasSystem) editor.removeFirst();
        } else {
            if (hasSystem) {
                editor.set(0, AIChatMessage.system(text));
            } else {
                editor.prepend(AIChatMessage.system(text));
            }
        }
    }

    private void submit(Runnable task) {
        if (executor.isShutdown()) return;
        try {
            executor.submit(task);
        } catch (RejectedExecutionException e) {
            log.warn("Task rejected — executor is shutting down");
        }
    }
}