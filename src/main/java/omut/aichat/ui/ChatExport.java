package omut.aichat.ui;

import javafx.stage.FileChooser;
import javafx.stage.Window;
import omut.aichat.chat.AIChatMessage;
import omut.aichat.chat.ChatExporter;
import omut.aichat.chat.ChatSession;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class ChatExport {

    private final ChatSession session;
    private final Supplier<Window> windowSupplier;
    private final SaveDialog saveDialog;
    private final Consumer<String> successHandler;
    private final Consumer<String> errorHandler;

    public ChatExport(
            ChatSession session,
            Supplier<Window> windowSupplier,
            SaveDialog saveDialog,
            Consumer<String> successHandler,
            Consumer<String> errorHandler
    ) {
        this.session = session;
        this.windowSupplier = windowSupplier;
        this.saveDialog = saveDialog;
        this.successHandler = successHandler;
        this.errorHandler = errorHandler;
    }

    public void onSave() {
        List<AIChatMessage> history = new ArrayList<>(session.getHistory());
        if (history.isEmpty()) {
            return;
        }

        File file = saveDialog.choose(windowSupplier.get(), defaultFileName());
        if (file == null) {
            return;
        }

        try {
            export(history, file);
            successHandler.accept("Saved: " + file.getName());
        } catch (IOException e) {
            errorHandler.accept("Save failed: " + e.getMessage());
        }
    }

    void export(List<AIChatMessage> history, File file) throws IOException {
        String name = file.getName().toLowerCase();

        if (name.endsWith(".json")) {
            ChatExporter.exportJson(history, file.toPath());
        } else if (name.endsWith(".html")) {
            ChatExporter.exportHtml(history, file.toPath());
        } else if (name.endsWith(".pdf")) {
            ChatExporter.exportPdf(history, file.toPath());
        } else {
            ChatExporter.exportMarkdown(history, file.toPath());
        }
    }

    String defaultFileName() {
        String model = session.currentModel().replace(":", "-");
        return "chat-" + model + "-" + LocalDate.now();
    }

    @FunctionalInterface
    public interface SaveDialog {

        File choose(Window owner, String defaultFileName);
    }

    public static File chooseSaveFile(Window owner, String defaultFileName) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save conversation");
        chooser.setInitialFileName(defaultFileName);
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Markdown (*.md)", "*.md"),
                new FileChooser.ExtensionFilter("JSON (*.json)", "*.json"),
                new FileChooser.ExtensionFilter("HTML (*.html)", "*.html"),
                new FileChooser.ExtensionFilter("PDF (*.pdf)", "*.pdf")
        );

        return chooser.showSaveDialog(owner);
    }
}

