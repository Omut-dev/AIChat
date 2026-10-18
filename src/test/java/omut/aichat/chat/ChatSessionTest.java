package omut.aichat.chat;

import omut.aichat.config.AppConfig;
import omut.aichat.config.AppSettings;
import omut.aichat.service.LlmService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ChatSessionTest {

    private LlmService llm;
    private AppConfig config;
    private ChatSession session;
    private RecordingListener listener;

    @BeforeEach
    void setUp() {
        llm = mock(LlmService.class);
        config = mock(AppConfig.class);

        when(config.systemPrompt()).thenReturn("");
        when(config.historyMaxMessages()).thenReturn(20);
        when(config.attachMaxChars()).thenReturn(100_000);
        when(config.getBaseUrl()).thenReturn("http://localhost:11434");
        when(config.temperature()).thenReturn(0.5);
        when(config.requestTimeoutMinutes()).thenReturn(5);

        when(llm.currentModel()).thenReturn("qwen2.5:7b");
        when(llm.isAvailable()).thenReturn(true);

        session = new ChatSession(llm, config);
        listener = new RecordingListener();
        session.addListener(listener);
    }

    /**
     * Wires LlmService.askStreaming to complete synchronously with the
     * given response. Idempotent — safe to call before every test.
     */
    private void completeImmediately(String reply, long durationMillis) {
        doAnswer(inv -> {
            StreamingCallback cb = inv.getArgument(1);
            cb.onComplete(new LlmResponse(reply, durationMillis, 10, 1_000_000_000L));
            return null;
        }).when(llm).askStreaming(any(), any());
    }

    private void failImmediately(Throwable error) {
        doAnswer(inv -> {
            StreamingCallback cb = inv.getArgument(1);
            cb.onError(error);
            return null;
        }).when(llm).askStreaming(any(), any());
    }

    /** Waits until the worker thread has finished both callbacks. */
    private void awaitIdle() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (listener.events.contains("thinkingFinished")
                    && listener.events.lastIndexOf("thinkingFinished")
                    > listener.events.lastIndexOf("thinkingStarted")) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("worker did not finish in time: " + listener.events);
    }

    /**
     * Waits until the listener has recorded the named event, up to 2s.
     * Replaces Thread.sleep in tests that just need to know "the
     * worker has delivered this signal". Fails with a clear message
     * listing what was actually seen, so a broken dispatch shows up
     * as a diagnostic, not as a mysterious timeout.
     */
    private void awaitEvent(String name) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (listener.events.contains(name)) return;
            Thread.onSpinWait();
        }
        throw new AssertionError("event not seen: " + name
                + "; saw: " + listener.events);
    }

    private static final class RecordingListener implements ChatListener {
        final List<String> events = new ArrayList<>();
        final AtomicReference<AIChatMessage> lastMessage = new AtomicReference<>();
        final AtomicReference<String> lastNotice = new AtomicReference<>();
        final AtomicReference<NoticeLevel> lastNoticeLevel = new AtomicReference<>();
        final AtomicReference<Boolean> lastStatus = new AtomicReference<>();

        @Override public void onMessage(AIChatMessage m) {
            lastMessage.set(m);
            events.add("message:" + m.role());
        }
        @Override public void onHistoryChanged() { events.add("historyChanged"); }
        @Override public void onThinkingStarted() { events.add("thinkingStarted"); }
        @Override public void onThinkingFinished() { events.add("thinkingFinished"); }
        @Override public void onStatusChanged(boolean available) {
            lastStatus.set(available);
            events.add("status:" + available);
        }
        @Override public void onCleared() { events.add("cleared"); }
        @Override public void onModelsLoaded(List<String> models) { events.add("modelsLoaded"); }
        @Override public void onModelChanged(String m) { events.add("modelChanged:" + m); }
        @Override public void onResponseTime(long ms) { events.add("responseTime"); }
        @Override public void onRequestCancelled() { events.add("requestCancelled"); }
        @Override public void onTokensPerSecond(double tps) { events.add("tokensPerSecond"); }
        @Override public void onToken(String c, String f) { events.add("token"); }
        @Override public void onNotice(String text, NoticeLevel level) {
            lastNotice.set(text);
            lastNoticeLevel.set(level);
            events.add("notice:" + level);
        }
    }

    @Nested
    @DisplayName("Constructor")
    class Init {

        @Test
        @DisplayName("Empty history when system prompt is blank")
        void noPrompt() {
            when(config.systemPrompt()).thenReturn("");
            ChatSession s = new ChatSession(llm, config);
            assertThat(s.getHistory()).isEmpty();
        }

        @Test
        @DisplayName("System message is prepended when prompt is non-blank")
        void withPrompt() {
            when(config.systemPrompt()).thenReturn("be concise");
            ChatSession s = new ChatSession(llm, config);

            List<AIChatMessage> history = s.getHistory();
            assertThat(history).hasSize(1);
            assertThat(history.getFirst().role()).isEqualTo(AIChatMessage.Role.SYSTEM);
            assertThat(history.getFirst().text()).isEqualTo("be concise");
        }
    }

    @Nested
    @DisplayName("checkAvailability")
    class Availability {

        @Test
        @DisplayName("Emits status(true) when the service is up")
        void available() throws Exception {
            when(llm.isAvailable()).thenReturn(true);
            session.checkAvailability();
            awaitEvent("status:true");
            assertThat(listener.lastStatus.get()).isTrue();
        }

        @Test
        @DisplayName("Emits status(false) when the service is down")
        void unavailable() throws Exception {
            when(llm.isAvailable()).thenReturn(false);
            session.checkAvailability();
            awaitEvent("status:false");
            assertThat(listener.lastStatus.get()).isFalse();
        }
    }

    @Nested
    @DisplayName("send")
    class Send {

        @Test
        @DisplayName("Happy path appends user then assistant, emits thinking events")
        void happy() throws Exception {
            completeImmediately("hello back", 100L);
            session.send("hello");
            awaitIdle();

            List<AIChatMessage> history = session.getHistory();
            assertThat(history).hasSize(2);
            assertThat(history.get(0).role()).isEqualTo(AIChatMessage.Role.USER);
            assertThat(history.get(0).text()).isEqualTo("hello");
            assertThat(history.get(1).role()).isEqualTo(AIChatMessage.Role.ASSISTANT);
            assertThat(history.get(1).text()).isEqualTo("hello back");

            assertThat(listener.events).contains("thinkingStarted", "thinkingFinished");
            assertThat(listener.events).contains("message:USER", "message:ASSISTANT");
        }

        @Test
        @DisplayName("Blank text is ignored")
        void blank() {
            session.send("   ");
            session.send("");
            session.send(null);
            assertThat(session.getHistory()).isEmpty();
            verify(llm, never()).askStreaming(any(), any());
        }

        @Test
        @DisplayName("Error from the model lands in history as SYSTEM")
        void error() throws Exception {
            failImmediately(new java.net.ConnectException("refused"));
            session.send("hello");
            awaitIdle();

            List<AIChatMessage> history = session.getHistory();
            assertThat(history).hasSize(2);
            assertThat(history.get(1).role()).isEqualTo(AIChatMessage.Role.SYSTEM);
            assertThat(history.get(1).text())
                    .isEqualTo("Cannot connect to Ollama. Is it running?");
        }

        @Test
        @DisplayName("Second send while a request is in flight is a no-op")
        void secondSendRejected() throws Exception {
            CountDownLatch blocker = new CountDownLatch(1);
            CountDownLatch entered = new CountDownLatch(1);
            doAnswer(inv -> {
                entered.countDown();
                StreamingCallback cb = inv.getArgument(1);
                blocker.await();
                cb.onComplete(new LlmResponse("first", 10L, 1, 1_000_000L));
                return null;
            }).when(llm).askStreaming(any(), any());

            session.send("first");
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            session.send("second");

            blocker.countDown();
            awaitIdle();

            List<AIChatMessage> history = session.getHistory();
            assertThat(history).extracting(AIChatMessage::text)
                    .containsExactly("first", "first");
            verify(llm, times(1)).askStreaming(any(), any());
        }
    }

    @Nested
    @DisplayName("cancelCurrentRequest")
    class Cancel {

        @Test
        @DisplayName("Appends cancel note, emits requestCancelled, clears token")
        void cancel() throws Exception {
            CountDownLatch blocker = new CountDownLatch(1);
            CountDownLatch entered = new CountDownLatch(1);
            doAnswer(inv -> {
                entered.countDown();
                boolean released = blocker.await(5, TimeUnit.SECONDS);
                if (!released) {
                    throw new IllegalStateException("test did not release the worker");
                }
                return null;
            }).when(llm).askStreaming(any(), any());

            session.send("hi");
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            session.cancelCurrentRequest();
            awaitEvent("requestCancelled");

            assertThat(listener.events).contains("requestCancelled");
            assertThat(session.getHistory())
                    .extracting(AIChatMessage::text)
                    .contains("Generation cancelled.");

            completeImmediately("ok", 5L);
            session.send("again");
            awaitIdle();
            verify(llm, times(2)).askStreaming(any(), any());
        }

        @Test
        @DisplayName("No-op when nothing is in flight")
        void noop() {
            session.cancelCurrentRequest();
            assertThat(listener.events).doesNotContain("requestCancelled");
            assertThat(session.getHistory()).isEmpty();
        }
    }

    @Nested
    @DisplayName("regenerateLast")
    class Regenerate {

        @Test
        @DisplayName("Cuts the trailing assistant and re-runs the last user")
        void rerun() throws Exception {
            completeImmediately("first answer", 50L);
            session.send("q");
            awaitIdle();
            assertThat(session.getHistory()).hasSize(2);

            completeImmediately("second answer", 50L);
            session.regenerateLast();
            awaitIdle();

            List<AIChatMessage> history = session.getHistory();
            assertThat(history).hasSize(2);
            assertThat(history.get(1).text()).isEqualTo("second answer");
        }

        @Test
        @DisplayName("Emits a notice when there is no USER message")
        void nothing() throws Exception {
            session.regenerateLast();
            awaitEvent("notice:INFO");
            assertThat(listener.lastNotice.get()).isEqualTo("Nothing to regenerate.");
            assertThat(listener.lastNoticeLevel.get()).isEqualTo(NoticeLevel.INFO);
            verify(llm, never()).askStreaming(any(), any());
        }
    }

    @Nested
    @DisplayName("editUserMessage")
    class Edit {

        @Test
        @DisplayName("Replaces the last USER and re-runs the request")
        void edit() throws Exception {
            completeImmediately("a1", 10L);
            session.send("original");
            awaitIdle();

            String userId = session.getHistory().getFirst().id();

            completeImmediately("a2", 10L);
            session.editUserMessage(userId, "edited");
            awaitIdle();

            List<AIChatMessage> history = session.getHistory();
            assertThat(history).hasSize(2);
            assertThat(history.get(0).text()).isEqualTo("edited");
            assertThat(history.get(1).text()).isEqualTo("a2");
            assertThat(listener.events).contains("message:USER");
        }

        @Test
        @DisplayName("Unknown id is a no-op")
        void unknown() {
            session.editUserMessage("nope", "text");
            assertThat(session.getHistory()).isEmpty();
            verify(llm, never()).askStreaming(any(), any());
        }

        @Test
        @DisplayName("During an active request emits an INFO notice, does not edit")
        void blockedWhileGenerating() throws Exception {
            CountDownLatch blocker = new CountDownLatch(1);
            CountDownLatch entered = new CountDownLatch(1);
            doAnswer(inv -> {
                entered.countDown();
                StreamingCallback cb = inv.getArgument(1);
                blocker.await(5, TimeUnit.SECONDS);
                cb.onComplete(new LlmResponse("done", 10L, 1, 1_000_000L));
                return null;
            }).when(llm).askStreaming(any(), any());

            session.send("original");
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            String userId = session.getHistory().getFirst().id();
            session.editUserMessage(userId, "edited");
            awaitEvent("notice:INFO");
            assertThat(listener.lastNotice.get()).isEqualTo("Cannot edit while generating.");

            blocker.countDown();
            awaitIdle();
            assertThat(session.getHistory().getFirst().text()).isEqualTo("original");
        }
    }

    @Nested
    @DisplayName("deleteFrom")
    class Delete {

        @Test
        @DisplayName("Truncates the transcript from the given id")
        void truncate() throws Exception {
            completeImmediately("a1", 10L);
            session.send("q1");
            awaitIdle();
            completeImmediately("a2", 10L);
            session.send("q2");
            awaitIdle();

            String q2Id = session.getHistory().get(2).id();
            session.deleteFrom(q2Id);

            assertThat(session.getHistory())
                    .extracting(AIChatMessage::text)
                    .containsExactly("q1", "a1");
            assertThat(listener.events).contains("historyChanged");
        }

        @Test
        @DisplayName("Refuses to delete a SYSTEM message")
        void refusesSystem() {
            when(config.systemPrompt()).thenReturn("prompt");
            ChatSession s = new ChatSession(llm, config);
            String sysId = s.getHistory().getFirst().id();
            s.deleteFrom(sysId);
            assertThat(s.getHistory()).hasSize(1);
        }

        @Test
        @DisplayName("During an active request emits an INFO notice, does not truncate")
        void blockedWhileGenerating() throws Exception {
            CountDownLatch blocker = new CountDownLatch(1);
            CountDownLatch entered = new CountDownLatch(1);
            doAnswer(inv -> {
                entered.countDown();
                StreamingCallback cb = inv.getArgument(1);
                blocker.await(5, TimeUnit.SECONDS);
                cb.onComplete(new LlmResponse("done", 10L, 1, 1_000_000L));
                return null;
            }).when(llm).askStreaming(any(), any());

            session.send("hi");
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            session.deleteFrom("whatever");
            awaitEvent("notice:INFO");
            assertThat(listener.lastNotice.get()).isEqualTo("Cannot delete while generating.");

            blocker.countDown();
            awaitIdle();
            assertThat(session.getHistory()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("switchModel")
    class Switch {

        @Test
        @DisplayName("Delegates to llmService, persists, emits modelChanged")
        void happy() throws Exception {
            session.switchModel("llama3:8b");
            awaitEvent("modelChanged:llama3:8b");
            verify(llm).switchModel("llama3:8b");
            verify(config).setSelectedModel("llama3:8b");
            assertThat(listener.events).contains("modelChanged:llama3:8b");
        }

        @Test
        @DisplayName("Blank name emits an ERROR notice, no delegation")
        void blank() {
            session.switchModel("   ");
            assertThat(listener.lastNoticeLevel.get()).isEqualTo(NoticeLevel.ERROR);
            verify(llm, never()).switchModel(any());
        }

        @Test
        @DisplayName("Exception from llmService becomes an ERROR notice")
        void exception() throws Exception {
            doThrow(new RuntimeException("boom")).when(llm).switchModel("bad");
            session.switchModel("bad");
            awaitEvent("notice:ERROR");
            assertThat(listener.lastNoticeLevel.get()).isEqualTo(NoticeLevel.ERROR);
            assertThat(listener.lastNotice.get()).contains("Failed to switch model");
        }
    }

    @Nested
    @DisplayName("applySettings")
    class ApplySettings {

        @Test
        @DisplayName("Persists every field, rebuilds model, emits notice")
        void apply() throws Exception {
            AppSettings s = new AppSettings(
                    "http://192.168.1.5:11434", 1.2, 10, 30, 50_000);
            session.applySettings(s);
            awaitEvent("notice:SUCCESS");

            verify(config).setTemperature(1.2);
            verify(config).setRequestTimeoutMinutes(10);
            verify(config).setHistoryMaxMessages(30);
            verify(config).setAttachMaxChars(50_000);
            verify(llm).rebuildModel();
            assertThat(listener.lastNotice.get()).isEqualTo("Settings saved.");
            assertThat(listener.lastNoticeLevel.get()).isEqualTo(NoticeLevel.SUCCESS);
        }

        @Test
        @DisplayName("Null is a no-op")
        void nullSettings() {
            session.applySettings(null);
            verify(llm, never()).rebuildModel();
        }
    }

    @Nested
    @DisplayName("setSystemPrompt")
    class SetPrompt {

        @Test
        @DisplayName("Prepends a SYSTEM message when none exists")
        void prepend() throws Exception {
            when(config.systemPrompt()).thenReturn("new prompt");
            session.setSystemPrompt("new prompt");
            awaitEvent("notice:SUCCESS");

            assertThat(session.getHistory()).hasSize(1);
            assertThat(session.getHistory().getFirst().role())
                    .isEqualTo(AIChatMessage.Role.SYSTEM);
            verify(config).setSystemPrompt("new prompt");
        }

        @Test
        @DisplayName("Emits historyChanged after updating the prompt")
        void emitsHistoryChanged() throws Exception {
            when(config.systemPrompt()).thenReturn("new");

            session.setSystemPrompt("new");
            awaitEvent("notice:SUCCESS");

            assertThat(listener.events).contains("historyChanged");
        }

        @Test
        @DisplayName("Replaces the existing SYSTEM message in place")
        void replace() throws Exception {
            when(config.systemPrompt()).thenReturn("old");
            ChatSession s = new ChatSession(llm, config);
            s.addListener(listener);
            when(config.systemPrompt()).thenReturn("new");
            s.setSystemPrompt("new");
            awaitEvent("notice:SUCCESS");

            List<AIChatMessage> history = s.getHistory();
            assertThat(history).hasSize(1);
            assertThat(history.getFirst().text()).isEqualTo("new");
        }

        @Test
        @DisplayName("Blank prompt removes the SYSTEM message")
        void remove() throws Exception {
            when(config.systemPrompt()).thenReturn("initial");
            ChatSession s = new ChatSession(llm, config);
            s.addListener(listener);
            when(config.systemPrompt()).thenReturn("");
            s.setSystemPrompt("   ");
            awaitEvent("notice:SUCCESS");

            assertThat(s.getHistory()).isEmpty();
        }
    }

    @Nested
    @DisplayName("clear")
    class Clear {

        @Test
        @DisplayName("Empties history, keeps prompt, emits cleared + notice")
        void clears() throws Exception {
            when(config.systemPrompt()).thenReturn("prompt");
            ChatSession s = new ChatSession(llm, config);
            s.addListener(listener);

            completeImmediately("a1", 10L);
            s.send("q1");
            awaitEvent("thinkingFinished");

            s.clear();
            awaitEvent("notice:INFO");

            assertThat(s.getHistory()).hasSize(1);
            assertThat(s.getHistory().getFirst().role())
                    .isEqualTo(AIChatMessage.Role.SYSTEM);
            assertThat(listener.events).contains("cleared");
            assertThat(listener.lastNotice.get()).isEqualTo("Chat cleared.");
        }
    }

    @Nested
    @DisplayName("attachFile")
    class Attach {

        @Test
        @DisplayName("Happy path appends a SYSTEM message with content")
        void happy() throws Exception {
            session.attachFile("notes.txt", "hello");
            awaitEvent("notice:SUCCESS");

            assertThat(session.getHistory()).hasSize(1);
            assertThat(session.getHistory().getFirst().role())
                    .isEqualTo(AIChatMessage.Role.SYSTEM);
            assertThat(session.getHistory().getFirst().text())
                    .contains("[Attached: notes.txt]")
                    .contains("hello");
            assertThat(listener.events).contains("message:SYSTEM");
        }

        @Test
        @DisplayName("Too large file emits an ERROR notice, nothing added")
        void tooLarge() {
            when(config.attachMaxChars()).thenReturn(10);
            session.attachFile("big.txt", "0123456789ABCDEF");
            assertThat(session.getHistory()).isEmpty();
            assertThat(listener.lastNoticeLevel.get()).isEqualTo(NoticeLevel.ERROR);
            assertThat(listener.lastNotice.get()).contains("File too large");
        }

        @Test
        @DisplayName("Empty content emits an ERROR notice, nothing added")
        void emptyContent() throws Exception {
            session.attachFile("empty.txt", "   ");
            awaitEvent("notice:ERROR");

            assertThat(session.getHistory()).isEmpty();
            assertThat(listener.lastNoticeLevel.get()).isEqualTo(NoticeLevel.ERROR);
            assertThat(listener.lastNotice.get()).contains("empty");
        }
    }

    @Nested
    @DisplayName("switchServer")
    class SwitchServer {

        @Test
        @DisplayName("Persists active id, rebuilds model, emits notice + status")
        void happy() throws Exception {
            when(config.serverName("srv-abcd1234")).thenReturn("Home");
            when(llm.isAvailable()).thenReturn(true);

            session.switchServer("srv-abcd1234");
            awaitEvent("notice:SUCCESS");
            awaitEvent("status:true");

            verify(config).setActiveServer("srv-abcd1234");
            verify(llm).rebuildModel();
            verify(llm).isAvailable();
            assertThat(listener.lastNotice.get())
                    .isEqualTo("Switched to server: Home");
            assertThat(listener.lastNoticeLevel.get()).isEqualTo(NoticeLevel.SUCCESS);
        }

        @Test
        @DisplayName("Blank id is a no-op")
        void blank() {
            session.switchServer("   ");
            session.switchServer(null);
            verify(config, never()).setActiveServer(any());
            verify(llm, never()).rebuildModel();
        }

        @Test
        @DisplayName("Exception from llmService.rebuildModel becomes an ERROR notice")
        void rebuildFails() throws Exception {
            doThrow(new RuntimeException("boom"))
                    .when(llm).rebuildModel();

            session.switchServer("srv-x");
            awaitEvent("notice:ERROR");

            assertThat(listener.lastNoticeLevel.get()).isEqualTo(NoticeLevel.ERROR);
            assertThat(listener.lastNotice.get())
                    .contains("Failed to switch server");
        }
    }

    @Nested
    @DisplayName("switchPrompt")
    class SwitchPrompt {

        @Test
        @DisplayName("Persists active id, applies prompt text, emits notice")
        void happy() throws Exception {
            when(config.promptName("prm-abcd1234")).thenReturn("Code reviewer");
            when(config.systemPrompt()).thenReturn("You are a strict reviewer");

            session.switchPrompt("prm-abcd1234");
            awaitEvent("notice:SUCCESS");

            verify(config).setActivePrompt("prm-abcd1234");
            assertThat(listener.lastNotice.get())
                    .isEqualTo("Switched to prompt: Code reviewer");
            assertThat(listener.lastNoticeLevel.get()).isEqualTo(NoticeLevel.SUCCESS);
        }

        @Test
        @DisplayName("Emits historyChanged after swapping the prompt")
        void emitsHistoryChanged() throws Exception {
            when(config.promptName("prm-x")).thenReturn("X");
            when(config.systemPrompt()).thenReturn("new prompt");

            session.switchPrompt("prm-x");
            awaitEvent("notice:SUCCESS");

            assertThat(listener.events).contains("historyChanged");
        }

        @Test
        @DisplayName("Replaces the existing SYSTEM message with the new prompt's text")
        void replacesSystem() throws Exception {
            when(config.systemPrompt()).thenReturn("initial prompt");
            ChatSession s = new ChatSession(llm, config);
            s.addListener(listener);

            assertThat(s.getHistory()).hasSize(1);
            assertThat(s.getHistory().getFirst().text()).isEqualTo("initial prompt");

            when(config.promptName("prm-x")).thenReturn("X");
            when(config.systemPrompt()).thenReturn("new prompt");

            s.switchPrompt("prm-x");
            awaitEvent("notice:SUCCESS");

            assertThat(s.getHistory()).hasSize(1);
            assertThat(s.getHistory().getFirst().role())
                    .isEqualTo(AIChatMessage.Role.SYSTEM);
            assertThat(s.getHistory().getFirst().text()).isEqualTo("new prompt");
        }

        @Test
        @DisplayName("Prepends SYSTEM when none exists, if new prompt text is non-blank")
        void prependsSystem() throws Exception {
            when(config.systemPrompt()).thenReturn("");
            ChatSession s = new ChatSession(llm, config);
            s.addListener(listener);

            assertThat(s.getHistory()).isEmpty();

            when(config.promptName("prm-x")).thenReturn("X");
            when(config.systemPrompt()).thenReturn("greetings");

            s.switchPrompt("prm-x");
            awaitEvent("notice:SUCCESS");

            assertThat(s.getHistory()).hasSize(1);
            assertThat(s.getHistory().getFirst().role())
                    .isEqualTo(AIChatMessage.Role.SYSTEM);
            assertThat(s.getHistory().getFirst().text()).isEqualTo("greetings");
        }

        @Test
        @DisplayName("Removes SYSTEM when new prompt text is blank")
        void removesSystem() throws Exception {
            when(config.systemPrompt()).thenReturn("initial");
            ChatSession s = new ChatSession(llm, config);
            s.addListener(listener);

            assertThat(s.getHistory()).hasSize(1);

            when(config.promptName("prm-empty")).thenReturn("Empty");
            when(config.systemPrompt()).thenReturn("");

            s.switchPrompt("prm-empty");
            awaitEvent("notice:SUCCESS");

            assertThat(s.getHistory()).isEmpty();
        }

        @Test
        @DisplayName("Blank id is a no-op")
        void blank() {
            session.switchPrompt("   ");
            session.switchPrompt(null);
            verify(config, never()).setActivePrompt(any());
        }

        @Test
        @DisplayName("Exception from config becomes an ERROR notice")
        void exception() throws Exception {
            doThrow(new RuntimeException("boom"))
                    .when(config).setActivePrompt("prm-bad");

            session.switchPrompt("prm-bad");
            awaitEvent("notice:ERROR");

            assertThat(listener.lastNoticeLevel.get()).isEqualTo(NoticeLevel.ERROR);
            assertThat(listener.lastNotice.get()).contains("Failed to switch prompt");
        }
    }
}