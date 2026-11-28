package omut.aichat.ui;

import omut.aichat.chat.AIChatMessage;
import omut.aichat.chat.ChatSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatExportTest {

    @TempDir
    Path tempDir;

    private ChatSession session;
    private AtomicReference<String> successMessage;
    private AtomicReference<String> errorMessage;
    private AtomicBoolean dialogCalled;

    private ChatExport chatExport;

    @BeforeEach
    void setUp() {
        session = mock(ChatSession.class);
        successMessage = new AtomicReference<>();
        errorMessage = new AtomicReference<>();
        dialogCalled = new AtomicBoolean(false);

        chatExport = new ChatExport(
                session,
                () -> null,
                (owner, fileName) -> {
                    dialogCalled.set(true);
                    return null;
                },
                successMessage::set,
                errorMessage::set
        );

        when(session.currentModel()).thenReturn("test-model");
    }

    @Test
    void onSaveShouldNotOpenDialogWhenHistoryIsEmpty() {
        when(session.getHistory()).thenReturn(List.of());

        chatExport.onSave();

        assertFalse(dialogCalled.get());
        assertNull(successMessage.get());
        assertNull(errorMessage.get());
    }

    @Test
    void onSaveShouldDoNothingWhenDialogIsCancelled() {
        when(session.getHistory()).thenReturn(List.of(AIChatMessage.user("Hello")));

        chatExport.onSave();

        assertTrue(dialogCalled.get());
        assertNull(successMessage.get());
        assertNull(errorMessage.get());
    }

    @Test
    void onSaveShouldExportMarkdownAndReportSuccess() {
        List<AIChatMessage> history = List.of(
                AIChatMessage.user("Hello"),
                AIChatMessage.assistant("Hi!", 100, "test-model")
        );

        when(session.getHistory()).thenReturn(history);

        File file = tempDir.resolve("chat.md").toFile();

        chatExport = new ChatExport(
                session,
                () -> null,
                (owner, fileName) -> file,
                successMessage::set,
                errorMessage::set
        );

        chatExport.onSave();

        assertTrue(file.exists());
        assertTrue(file.length() > 0);
        assertEquals("Saved: chat.md", successMessage.get());
        assertNull(errorMessage.get());
    }

    @Test
    void onSaveShouldReportErrorWhenExportFails() {
        when(session.getHistory()).thenReturn(List.of(AIChatMessage.user("Hello")));

        File directory = tempDir.toFile();

        chatExport = new ChatExport(
                session,
                () -> null,
                (owner, fileName) -> directory,
                successMessage::set,
                errorMessage::set
        );

        chatExport.onSave();

        assertNull(successMessage.get());
        assertNotNull(errorMessage.get());
        assertTrue(errorMessage.get().startsWith("Save failed: "));
    }

    @Test
    void exportShouldSupportAllFormats() throws IOException {
        List<AIChatMessage> history = List.of(
                AIChatMessage.user("Hello"),
                AIChatMessage.assistant("Hi!", 100, "test-model")
        );

        for (String extension : List.of("md", "json", "html", "pdf")) {
            File file = tempDir.resolve("chat." + extension).toFile();

            chatExport.export(history, file);

            assertTrue(file.exists(), "File was not created for ." + extension);
            assertTrue(file.length() > 0, "File is empty for ." + extension);
        }

        byte[] pdf = Files.readAllBytes(tempDir.resolve("chat.pdf"));
        assertEquals("%PDF-", new String(pdf, 0, 5));
    }

    @Test
    void defaultFileNameShouldContainModelAndCurrentDate() {
        when(session.currentModel()).thenReturn("llama3:latest");

        assertEquals(
                "chat-llama3-latest-" + LocalDate.now(),
                chatExport.defaultFileName()
        );
    }
}