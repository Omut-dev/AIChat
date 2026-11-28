package omut.aichat.ui;

import javafx.animation.PauseTransition;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.Parent;
import javafx.scene.control.Toggle;
import javafx.stage.FileChooser;
import omut.aichat.chat.AIChatMessage;
import omut.aichat.chat.ChatListener;
import omut.aichat.chat.ChatSession;
import omut.aichat.chat.NoticeLevel;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Window;
import javafx.util.Duration;
// Wildcard import: JSObject is deprecated since JDK 24 and IDEA
// flags a single-type import, though the type itself still works.
// There is no replacement; JSObject is how we expose the bridge.
import netscape.javascript.*;
import omut.aichat.config.AppConfig;
import omut.aichat.config.AppSettings;
import omut.aichat.utils.TimeFormat;
import omut.aichat.utils.UrlParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class ChatView implements ChatListener {

    private static final Logger log = LoggerFactory.getLogger(ChatView.class);
    private static final long STREAM_RENDER_INTERVAL_NANOS = 80_000_000L;

    private PauseTransition noticeTimer;

    private final AssetLoader assets = new AssetLoader();
    private final MarkdownRenderer markdown = new MarkdownRenderer();
    private final ChatViewBuilder view = new ChatViewBuilder();
    private final ChatSession session;
    private final AppConfig config;
    private final HostServices hostServices;
    private final ChatJsBridge jsBridge;
    private final ChatScripts scripts;
    private final ChatSearch search;
    private final ChatExport chatExport;

    private volatile long lastStreamRenderNanos = 0;
    private boolean busy = false;
    private boolean llmAvailable = false;
    private boolean suppressServerEvent = false;
    private boolean suppressPromptEvent = false;

    public ChatView(
            ChatSession session,
            AppConfig config,
            HostServices hostServices
    ) {
        this.session = session;
        this.config = config;
        this.hostServices = hostServices;
        this.session.addListener(this);
        this.jsBridge = new ChatJsBridge(session, this::openEditDialog);
        this.scripts = new ChatScripts(view.chatView.getEngine());
        this.search = new ChatSearch(scripts, (total, current) -> {
            boolean hasMatches = total > 0;
            view.searchPrevButton.setDisable(!hasMatches);
            view.searchNextButton.setDisable(!hasMatches);

            if (total == 0) {
                view.searchCounter.setText("");
            } else {
                view.searchCounter.setText((current + 1) + " of " + total);
            }
        });
        this.chatExport = new ChatExport(
                session,
                this::window,
                ChatExport::chooseSaveFile,
                this::showSuccess,
                this::showError
        );
    }

    public Parent build() {
        Parent root = view.build();

        view.exitMenuItem.setOnAction(_ -> Platform.exit());
        view.aboutMenuItem.setOnAction(_ -> onAbout());
        view.attachButton.setOnAction(_ -> onAttach());
        view.sendButton.setOnAction(_ -> onSend());
        view.inputField.setOnAction(_ -> onSend());
        view.clearButton.setOnAction(_ -> session.clear());
        view.checkButton.setOnAction(_ -> session.checkAvailability());
        view.saveButton.setOnAction(_ -> chatExport.onSave());
        view.copyLastButton.setOnAction(_ -> onCopyLast());
        view.stopButton.setOnAction(_ -> session.cancelCurrentRequest());
        view.regenerateButton.setOnAction(_ -> session.regenerateLast());
        view.settingsButton.setOnAction(_ -> onSettings());
        view.refreshModelsMenuItem.setOnAction(_ -> session.loadModels());

        view.searchField.textProperty().addListener((_, _, newText) ->
                search.onSearchQueryChanged(newText));

        view.searchField.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                if (event.isShiftDown()) {
                    search.onSearchPrev();
                } else {
                    search.onSearchNext();
                }
                event.consume();
            } else if (event.getCode() == KeyCode.ESCAPE) {
                view.searchField.clear();
                view.inputField.requestFocus();
                event.consume();
            }
        });

        view.searchPrevButton.setOnAction(_ -> search.onSearchPrev());
        view.searchNextButton.setOnAction(_ -> search.onSearchNext());

        view.promptSelector.setOnAction(_ -> {
            if (suppressPromptEvent) return;
            PromptItem item = view.promptSelector.getValue();
            if (item == null) return;
            if (item.id().equals(config.activePromptId())) return;
            session.switchPrompt(item.id());
        });

        view.serverSelector.setOnAction(_ -> {
            if (suppressServerEvent) return;
            ServerItem item = view.serverSelector.getValue();
            if (item == null) return;
            if (item.id().equals(config.activeServerId())) return;
            session.switchServer(item.id());
        });

        view.modelSelector.setOnAction(_ -> {
            String selected = view.modelSelector.getValue();
            if (selected != null && !selected.equals(session.currentModel())) {
                session.switchModel(selected);
            }
        });

        view.chatView.getEngine().getLoadWorker().stateProperty().addListener(
                (_, _, newState) -> {
                    if (newState == Worker.State.SUCCEEDED) {
                        registerJsBridge();
                        renderTranscript();
                        scripts.scrollToBottom();
                    }
                });
        view.themeGroup.selectedToggleProperty().addListener((_, _, newToggle) -> {
            if (newToggle == null) return;
            String themeId = (String) newToggle.getUserData();
            applyTheme(themeId);
        });

        String currentId = config.theme();
        for (Toggle toggle : view.themeGroup.getToggles()) {
            if (currentId.equals(toggle.getUserData())) {
                toggle.setSelected(true);
                break;
            }
        }

        reloadTemplate();
        refreshPromptSelector();
        refreshServerSelector();

        Platform.runLater(session::checkAvailability);
        installAccelerators(root);
        return root;
    }

    private void refreshPromptSelector() {
        suppressPromptEvent = true;
        try {
            List<PromptItem> items = new ArrayList<>();
            String activeId = config.activePromptId();
            PromptItem active = null;
            for (String id : config.promptIds()) {
                PromptItem item = new PromptItem(id, config.promptName(id));
                items.add(item);
                if (id.equals(activeId)) active = item;
            }
            view.promptSelector.getItems().setAll(items);
            view.promptSelector.setValue(active);
            view.promptSelector.setDisable(items.isEmpty());
        } finally {
            suppressPromptEvent = false;
        }
    }

    private void refreshServerSelector() {
        suppressServerEvent = true;
        try {
            List<ServerItem> items = new ArrayList<>();
            String activeId = config.activeServerId();
            ServerItem active = null;
            for (String id : config.serverIds()) {
                ServerItem item = new ServerItem(id, config.serverName(id));
                items.add(item);
                if (id.equals(activeId)) active = item;
            }
            view.serverSelector.getItems().setAll(items);
            view.serverSelector.setValue(active);
            view.serverSelector.setDisable(items.isEmpty());
        } finally {
            suppressServerEvent = false;
        }
    }

    @Override
    public void onStatusChanged(boolean available) {
        Platform.runLater(() -> {
            llmAvailable = available;
            if (available) {
                view.statusLabel.setText("Status: Ollama is available (" + hostLabel() + ")  |  Model: " + session.currentModel());
                setStatusStyle(true);
                if (view.modelSelector.getItems().isEmpty()) {
                    session.loadModels();
                }
            } else {
                view.statusLabel.setText("Status: Ollama is NOT available (" + hostLabel() + ")");
                setStatusStyle(false);
            }
            updateControls();
        });
    }

    @Override
    public void onModelsLoaded(List<String> models) {
        Platform.runLater(() -> {
            view.modelSelector.getItems().setAll(models);
            if (!models.isEmpty()) {
                view.modelSelector.setValue(session.currentModel());
            }
            updateControls();
        });
    }

    @Override
    public void onModelChanged(String modelName) {
        Platform.runLater(() -> {
            view.statusLabel.setText("Status: Ollama is available (" + hostLabel() + ")  |  Model: " + modelName);
            setStatusStyle(true);
        });
    }

    @Override
    public void onMessage(AIChatMessage message) {
        Platform.runLater(this::renderTranscript);
    }

    @Override
    public void onHistoryChanged() {
        Platform.runLater(this::renderTranscript);
    }

    @Override
    public void onThinkingStarted() {
        Platform.runLater(() -> {
            busy = true;
            view.typingLabel.setText("AI is thinking...");
            view.stopButton.setDisable(false);
            updateControls();
            scripts.beginStreaming();
            scripts.setBusy(true);
        });
    }

    @Override
    public void onThinkingFinished() {
        Platform.runLater(() -> {
            busy = false;
            view.typingLabel.setText("");
            view.stopButton.setDisable(true);
            updateControls();
            view.inputField.requestFocus();
            scripts.setBusy(false);
        });
    }

    @Override
    public void onNotice(String text, NoticeLevel level) {
        String cssClass = switch (level) {
            case INFO -> "notice-info";
            case SUCCESS -> "notice-success";
            case ERROR -> "notice-error";
        };
        showNotice(text, cssClass);
    }

    @Override
    public void onCleared() {
        Platform.runLater(() -> {
            reloadTemplate();
            view.noticeLabel.setText("");
            view.searchField.clear();
            search.reset();
        });
    }

    @Override
    public void onResponseTime(long millis) {
        Platform.runLater(() -> view.lastResponseLabel.setText("Last: " + TimeFormat.shortDuration(millis)));
    }

    @Override
    public void onRequestCancelled() {
        Platform.runLater(() -> {
            busy = false;
            view.typingLabel.setText("");
            view.stopButton.setDisable(true);
            updateControls();
            view.inputField.requestFocus();
            scripts.setBusy(false);
        });
    }

    @Override
    public void onTokensPerSecond(double tps) {
        Platform.runLater(() -> {
            if (tps > 0) {
                view.speedLabel.setText(String.format(java.util.Locale.US, "%.1f tok/s", tps));
            } else {
                view.speedLabel.setText("");
            }
        });
    }

    private void onAttach() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Attach file");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Text files", "*.txt", "*.md", "*.json", "*.xml", "*.yaml", "*.yml"),
                new FileChooser.ExtensionFilter("Code files", "*.java", "*.kt", "*.py", "*.js", "*.ts", "*.cpp", "*.c", "*.h"),
                new FileChooser.ExtensionFilter("All files", "*.*")
        );

        File file = chooser.showOpenDialog(window());
        if (file == null) return;

        try {
            String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            session.attachFile(file.getName(), content);
        } catch (IOException e) {
            showError("Attach failed: " + e.getMessage());
        }
    }

    @Override
    public void onToken(String chunk, String fullText) {
        long now = System.nanoTime();
        if (now - lastStreamRenderNanos < STREAM_RENDER_INTERVAL_NANOS) {
            return;
        }
        lastStreamRenderNanos = now;

        Platform.runLater(() -> {
            String html = markdown.renderToHtml(fullText);
            scripts.updateStreamingMessage(html);
            scripts.scrollIfAtBottom();
        });
    }

    public void focusInput() {
        view.inputField.requestFocus();
    }

    private void onSend() {
        String text = view.inputField.getText();
        if (text == null || text.isBlank()) return;
        if (busy) return;
        session.send(text);
        view.inputField.clear();
    }

    private void updateControls() {
        boolean locked = busy || !llmAvailable;
        boolean hasAssistant = hasAssistantMessage();
        view.inputField.setDisable(locked);
        view.attachButton.setDisable(locked);
        view.sendButton.setDisable(locked);
        view.clearButton.setDisable(busy);
        view.saveButton.setDisable(busy);
        view.copyLastButton.setDisable(!hasAssistant);
        view.regenerateButton.setDisable(locked || !hasAssistant);
        view.modelSelector.setDisable(locked || view.modelSelector.getItems().isEmpty());
        view.serverSelector.setDisable(busy || view.serverSelector.getItems().isEmpty());
        view.promptSelector.setDisable(busy || view.promptSelector.getItems().isEmpty());
    }

    private boolean hasAssistantMessage() {
        for (AIChatMessage m : session.getHistory()) {
            if (m.role() == AIChatMessage.Role.ASSISTANT) return true;
        }
        return false;
    }

    private String openEditDialog(String messageId) {
        AIChatMessage msg = findMessageById(messageId);
        if (msg == null) return null;
        return ChatDialogs.editMessage(window(), msg.text());
    }

    private Window window() {
        return view.chatView.getScene().getWindow();
    }

    private AIChatMessage findMessageById(String messageId) {
        for (AIChatMessage m : session.getHistory()) {
            if (m.id().equals(messageId)) return m;
        }
        return null;
    }

    private void onSettings() {
        String promptBefore = config.systemPrompt();

        AppSettings current = new AppSettings(
                config.getBaseUrl(),
                config.temperature(),
                config.requestTimeoutMinutes(),
                config.historyMaxMessages(),
                config.attachMaxChars()
        );
        AppSettings defaults = new AppSettings(
                config.defaultBaseUrl(),
                config.defaultTemperature(),
                config.defaultRequestTimeoutMinutes(),
                config.defaultHistoryMaxMessages(),
                config.defaultAttachMaxChars()
        );
        AppSettings result = ChatDialogs.editSettings(window(), current, defaults, config);
        if (result != null) {
            session.applySettings(result);

            if (!promptBefore.equals(config.systemPrompt())) {
                session.setSystemPrompt(config.systemPrompt());
            }
        }
        refreshPromptSelector();
        refreshServerSelector();
    }

    private void onCopyLast() {
        AIChatMessage last = lastAssistantMessage();
        if (last == null) {
            showInfo("Nothing to copy");
            return;
        }
        String plain = MarkdownRenderer.stripMarkdown(last.text());
        ClipboardContent content = new ClipboardContent();
        content.putString(plain);
        Clipboard.getSystemClipboard().setContent(content);
        showSuccess("Copied last response");
    }

    private AIChatMessage lastAssistantMessage() {
        List<AIChatMessage> history = session.getHistory();
        for (int i = history.size() - 1; i >= 0; i--) {
            AIChatMessage m = history.get(i);
            if (m.role() == AIChatMessage.Role.ASSISTANT) {
                return m;
            }
        }
        return null;
    }

    private void showInfo(String text) {
        showNotice(text, "notice-info");
    }

    private void showSuccess(String text) {
        showNotice(text, "notice-success");
    }

    private void showError(String text) {
        showNotice(text, "notice-error");
    }

    private void showNotice(String text, String styleClass) {
        Platform.runLater(() -> {
            view.noticeLabel.setText(text);
            view.noticeLabel.getStyleClass().removeAll("notice-info", "notice-success", "notice-error");
            view.noticeLabel.getStyleClass().add(styleClass);

            if (noticeTimer != null) {
                noticeTimer.stop();
            }
            noticeTimer = new PauseTransition(Duration.seconds(3));
            noticeTimer.setOnFinished(_ -> view.noticeLabel.setText(""));
            noticeTimer.play();
        });
    }

    private void renderTranscript() {
        String json = TranscriptJson.toJson(session.getHistory(), markdown);
        scripts.renderTranscript(json);
        search.reapplySearchIfActive(view.searchField.getText());
    }

    private void reloadTemplate() {
        view.chatView.getEngine().loadContent(
                assets.wrapInHtml("", currentTheme(), config)
        );
    }

    private void onAbout() {
        ChatDialogs.showAbout(window(), hostServices);
    }

    @SuppressWarnings("removal")
    private void registerJsBridge() {
        try {
            JSObject window = (JSObject) view.chatView.getEngine().executeScript("window");
            window.setMember("javaBridge", jsBridge);
        } catch (Exception e) {
            log.warn("Failed to register JS bridge: {}", e.getMessage());
        }
    }

    private void installAccelerators(Parent root) {
        root.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.isControlDown() && event.getCode() == KeyCode.L) {
                session.clear();
                event.consume();
            } else if (event.isControlDown() && event.getCode() == KeyCode.K) {
                view.modelSelector.requestFocus();
                event.consume();
            } else if (event.getCode() == KeyCode.ESCAPE) {
                view.inputField.requestFocus();
                event.consume();
            }
        });
    }

    private void applyTheme(String themeId) {
        Theme theme = Theme.fromId(themeId);
        if (theme.id().equals(config.theme())) {
            return;
        }
        config.setTheme(theme.id());
        theme.apply();
        reloadTemplate();
    }

    private Theme currentTheme() {
        return Theme.fromId(config.theme());
    }

    private void setStatusStyle(boolean available) {
        view.statusLabel.getStyleClass().removeAll(
                "status-available", "status-unavailable", "status-unknown");
        view.statusLabel.getStyleClass().add(
                available ? "status-available" : "status-unavailable");
    }

    private String hostLabel() {
        String id = config.activeServerId();
        String name = config.serverName(id);
        String url = config.serverBaseUrl(id);
        if (url == null || url.isBlank()) return name + ", unknown";

        return name + ", " + UrlParser.host(url);
    }
}
