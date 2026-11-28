package omut.aichat.chat;

import omut.aichat.config.AppConfig;
import omut.aichat.config.AppSettings;
import omut.aichat.service.LlmService;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.shutdown();
        }
    }

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
        void noPrompt() {
            when(config.systemPrompt()).thenReturn("");
            ChatSession s = new ChatSession(llm, config);
            assertThat(s.getHistory()).isEmpty();
        }

        @Test
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
        void available() {
            when(llm.isAvailable()).thenReturn(true);
            session.checkAvailability();
            awaitEvent("status:true");
            assertThat(listener.lastStatus.get()).isTrue();
        }

        @Test
        void unavailable() {
            when(llm.isAvailable()).thenReturn(false);
            session.checkAvailability();
            awaitEvent("status:false");
            assertThat(listener.lastStatus.get()).isFalse();
        }
    }

    @Nested
    @DisplayName("loadModels")
    class LoadModels {
        @Test
        void happy() {
            when(llm.listModels()).thenReturn(List.of("a", "b"));
            session.loadModels();
            awaitEvent("modelsLoaded");
            verify(llm).listModels();
        }

        @Test
        void exceptionIsSwallowed() {
            when(llm.listModels()).thenThrow(new RuntimeException("boom"));
            session.loadModels();
            awaitEvent("modelsLoaded");
            assertThat(listener.events).contains("modelsLoaded");
        }
    }

    @Nested
    @DisplayName("send")
    class Send {

        @Test
        void happy() {
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
        void blank() {
            session.send("   ");
            session.send("");
            session.send(null);
            assertThat(session.getHistory()).isEmpty();
            verify(llm, never()).askStreaming(any(), any());
        }

        @Test
        void error() {
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

        @Test
        void thrownException() {
            doThrow(new RuntimeException("out of memory"))
                    .when(llm).askStreaming(any(), any());

            session.send("hello");
            awaitIdle();

            List<AIChatMessage> history = session.getHistory();

            assertThat(history).hasSize(2);
            assertThat(history.get(1).role()).isEqualTo(AIChatMessage.Role.SYSTEM);
            assertThat(history.get(1).text())
                    .isEqualTo("Not enough RAM to load the model. Try a smaller model (e.g. gemma2:2b) or close other apps.");

            assertThat(listener.events).contains("thinkingStarted", "thinkingFinished");
        }

        @Test
        void shutdownRejectsSend() {
            session.shutdown();
            session.send("hi");
            verify(llm, never()).askStreaming(any(), any());
        }
    }

    @Nested
    @DisplayName("cancelCurrentRequest")
    class Cancel {

        @Test
        void cancel() throws Exception {
            CountDownLatch blocker = new CountDownLatch(1);
            CountDownLatch entered = new CountDownLatch(1);
            doAnswer(_ -> {
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
        void noop() {
            session.cancelCurrentRequest();
            assertThat(listener.events).doesNotContain("requestCancelled");
            assertThat(session.getHistory()).isEmpty();
        }

        @Test
        void cancelAfterShutdownIsNoop() {
            session.shutdown();
            session.cancelCurrentRequest();
            assertThat(listener.events).doesNotContain("requestCancelled");
        }

        @Test
        void cancelTwiceOnlyEmitsOnce() throws Exception {
            CountDownLatch blocker = new CountDownLatch(1);
            CountDownLatch entered = new CountDownLatch(1);
            doAnswer(_ -> {
                entered.countDown();
                assertTrue(blocker.await(5, TimeUnit.SECONDS), "Timed out waiting for blocker");
                return null;
            }).when(llm).askStreaming(any(), any());

            session.send("hi");
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            session.cancelCurrentRequest();
            session.cancelCurrentRequest();

            awaitEvent("requestCancelled");
            long count = listener.events.stream()
                    .filter("requestCancelled"::equals)
                    .count();
            assertThat(count).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("regenerateLast")
    class Regenerate {

        @Test
        void rerun() {
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
        void nothing() {
            session.regenerateLast();
            awaitEvent("notice:INFO");
            assertThat(listener.lastNotice.get()).isEqualTo("Nothing to regenerate.");
            assertThat(listener.lastNoticeLevel.get()).isEqualTo(NoticeLevel.INFO);
            verify(llm, never()).askStreaming(any(), any());
        }

        @Test
        void noopWhenGenerating() throws Exception {
            CountDownLatch blocker = new CountDownLatch(1);
            CountDownLatch entered = new CountDownLatch(1);
            doAnswer(_ -> {
                entered.countDown();
                if (!blocker.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test did not release the worker in time");
                }
                return null;
            }).when(llm).askStreaming(any(), any());

            session.send("q");
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            session.regenerateLast();

            assertThat(listener.events).doesNotContain("historyChanged");

            blocker.countDown();
        }

        @Test
        void noopAfterShutdown() {
            session.shutdown();
            session.regenerateLast();
            verify(llm, never()).askStreaming(any(), any());
        }
    }

    @Nested
    @DisplayName("editUserMessage")
    class Edit {

        @Test
        void edit() {
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
        void unknown() {
            session.editUserMessage("nope", "text");
            assertThat(session.getHistory()).isEmpty();
            verify(llm, never()).askStreaming(any(), any());
        }

        @Test
        void blankArguments() {
            session.editUserMessage(null, "text");
            session.editUserMessage("", "text");
            session.editUserMessage("id", null);
            session.editUserMessage("id", "  ");
            assertThat(session.getHistory()).isEmpty();
        }

        @Test
        void blockedWhileGenerating() throws Exception {
            CountDownLatch blocker = new CountDownLatch(1);
            CountDownLatch entered = new CountDownLatch(1);

            doAnswer(inv -> {
                entered.countDown();
                boolean released = blocker.await(5, TimeUnit.SECONDS);
                if (!released) {
                    throw new IllegalStateException("test did not release the worker");
                }
                StreamingCallback cb = inv.getArgument(1);
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

        @Test
        void notAUserMessage() {
            when(config.systemPrompt()).thenReturn("prompt");
            ChatSession s = new ChatSession(llm, config);
            String sysId = s.getHistory().getFirst().id();
            s.editUserMessage(sysId, "new");
            assertThat(s.getHistory().getFirst().text()).isEqualTo("prompt");
        }

        @Test
        void afterShutdown() {
            session.shutdown();
            session.editUserMessage("id", "text");
            verify(llm, never()).askStreaming(any(), any());
        }
    }

    @Nested
    @DisplayName("deleteFrom")
    class Delete {

        @Test
        void truncate() {
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
        void refusesSystem() {
            when(config.systemPrompt()).thenReturn("prompt");
            ChatSession s = new ChatSession(llm, config);
            String sysId = s.getHistory().getFirst().id();
            s.deleteFrom(sysId);
            assertThat(s.getHistory()).hasSize(1);
        }

        @Test
        void blankArguments() {
            session.deleteFrom(null);
            session.deleteFrom("");
            session.deleteFrom("  ");
            assertThat(session.getHistory()).isEmpty();
        }

        @Test
        void unknownId() {
            session.deleteFrom("missing");
            assertThat(session.getHistory()).isEmpty();
        }

        @Test
        void blockedWhileGenerating() throws Exception {
            CountDownLatch blocker = new CountDownLatch(1);
            CountDownLatch entered = new CountDownLatch(1);

            doAnswer(inv -> {
                entered.countDown();
                boolean released = blocker.await(5, TimeUnit.SECONDS);
                if (!released) {
                    throw new IllegalStateException("test did not release the worker");
                }
                StreamingCallback cb = inv.getArgument(1);
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

        @Test
        void afterShutdown() {
            session.shutdown();
            session.deleteFrom("id");
            assertThat(session.getHistory()).isEmpty();
        }
    }

    @Nested
    @DisplayName("switchModel")
    class Switch {

        @Test
        void happy() {
            session.switchModel("llama3:8b");
            awaitEvent("modelChanged:llama3:8b");
            verify(llm).switchModel("llama3:8b");
            verify(config).setSelectedModel("llama3:8b");
            assertThat(listener.events).contains("modelChanged:llama3:8b");
        }

        @Test
        void blank() {
            session.switchModel("   ");
            assertThat(listener.lastNoticeLevel.get()).isEqualTo(NoticeLevel.ERROR);
            verify(llm, never()).switchModel(any());
        }

        @Test
        void exception() {
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
        void apply() {
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
        void nullSettings() {
            session.applySettings(null);
            verify(llm, never()).rebuildModel();
        }
    }

    @Nested
    @DisplayName("setSystemPrompt")
    class SetPrompt {

        @Test
        void prepend() {
            when(config.systemPrompt()).thenReturn("new prompt");
            session.setSystemPrompt("new prompt");
            awaitEvent("notice:SUCCESS");

            assertThat(session.getHistory()).hasSize(1);
            assertThat(session.getHistory().getFirst().role())
                    .isEqualTo(AIChatMessage.Role.SYSTEM);
            verify(config).setSystemPrompt("new prompt");
        }

        @Test
        void emitsHistoryChanged() {
            when(config.systemPrompt()).thenReturn("new");

            session.setSystemPrompt("new");
            awaitEvent("notice:SUCCESS");

            assertThat(listener.events).contains("historyChanged");
        }

        @Test
        void replace() {
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
        void remove() {
            when(config.systemPrompt()).thenReturn("initial");
            ChatSession s = new ChatSession(llm, config);
            s.addListener(listener);
            when(config.systemPrompt()).thenReturn("");
            s.setSystemPrompt("   ");
            awaitEvent("notice:SUCCESS");

            assertThat(s.getHistory()).isEmpty();
        }

        @Test
        void exceptionFromConfig() {
            doThrow(new RuntimeException("boom"))
                    .when(config).setSystemPrompt(anyString());
            session.setSystemPrompt("new");
            awaitEvent("notice:ERROR");
            assertThat(listener.lastNoticeLevel.get()).isEqualTo(NoticeLevel.ERROR);
            assertThat(listener.lastNotice.get()).contains("Failed to update system prompt");
        }
    }

    @Nested
    @DisplayName("clear")
    class Clear {

        @Test
        void clears() {
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
        void happy() {
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
        void tooLarge() {
            when(config.attachMaxChars()).thenReturn(10);
            session.attachFile("big.txt", "0123456789ABCDEF");
            assertThat(session.getHistory()).isEmpty();
            assertThat(listener.lastNoticeLevel.get()).isEqualTo(NoticeLevel.ERROR);
            assertThat(listener.lastNotice.get()).contains("File too large");
        }

        @Test
        void emptyContent() {
            session.attachFile("empty.txt", "   ");
            awaitEvent("notice:ERROR");

            assertThat(session.getHistory()).isEmpty();
            assertThat(listener.lastNoticeLevel.get()).isEqualTo(NoticeLevel.ERROR);
            assertThat(listener.lastNotice.get()).contains("empty");
        }

        @Test
        void blankFileName() {
            session.attachFile(null, "content");
            session.attachFile("", "content");
            session.attachFile("  ", "content");
            assertThat(session.getHistory()).isEmpty();
        }
    }

    @Nested
    @DisplayName("switchServer")
    class SwitchServer {

        @Test
        void happy() {
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
        void blank() {
            session.switchServer("   ");
            session.switchServer(null);
            verify(config, never()).setActiveServer(any());
            verify(llm, never()).rebuildModel();
        }

        @Test
        void rebuildFails() {
            doThrow(new RuntimeException("boom"))
                    .when(llm).rebuildModel();

            session.switchServer("srv-x");
            awaitEvent("notice:ERROR");

            assertThat(listener.lastNoticeLevel.get()).isEqualTo(NoticeLevel.ERROR);
            assertThat(listener.lastNotice.get())
                    .contains("Failed to rebuild model");
        }
    }

    @Nested
    @DisplayName("switchPrompt")
    class SwitchPrompt {

        @Test
        void happy() {
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
        void emitsHistoryChanged() {
            when(config.promptName("prm-x")).thenReturn("X");
            when(config.systemPrompt()).thenReturn("new prompt");

            session.switchPrompt("prm-x");
            awaitEvent("notice:SUCCESS");

            assertThat(listener.events).contains("historyChanged");
        }

        @Test
        void replacesSystem() {
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
        void prependsSystem() {
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
        void removesSystem() {
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
        void blank() {
            session.switchPrompt("   ");
            session.switchPrompt(null);
            verify(config, never()).setActivePrompt(any());
        }

        @Test
        void exception() {
            doThrow(new RuntimeException("boom"))
                    .when(config).setActivePrompt("prm-bad");

            session.switchPrompt("prm-bad");
            awaitEvent("notice:ERROR");

            assertThat(listener.lastNoticeLevel.get()).isEqualTo(NoticeLevel.ERROR);
            assertThat(listener.lastNotice.get()).contains("Failed to switch prompt");
        }
    }

    @Nested
    @DisplayName("Concurrency and threading")
    class Concurrency {

        @Test
        void deferredRebuildOnError() throws Exception {
            CountDownLatch streamBlocked = new CountDownLatch(1);
            CountDownLatch releaseStream = new CountDownLatch(1);

            doAnswer(inv -> {
                streamBlocked.countDown();
                if (!releaseStream.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("timeout");
                }
                StreamingCallback cb = inv.getArgument(1);
                cb.onError(new RuntimeException("boom"));
                return null;
            }).when(llm).askStreaming(any(), any());

            session.send("hello");
            assertThat(streamBlocked.await(2, TimeUnit.SECONDS)).isTrue();

            CountDownLatch modelSwitched = new CountDownLatch(1);

            doAnswer(_ -> {
                modelSwitched.countDown();
                return null;
            }).when(llm).switchModel("new-model");

            session.switchModel("new-model");

            assertThat(modelSwitched.await(2, TimeUnit.SECONDS)).isTrue();
            awaitEvent("modelChanged:new-model");

            verify(llm, never()).rebuildModel();

            releaseStream.countDown();
            awaitIdle();

            verify(llm, times(1)).rebuildModel();
        }

        @Test
        void multipleRebuildsDuringStream() throws Exception {
            CountDownLatch streamBlocked = new CountDownLatch(1);
            CountDownLatch releaseStream = new CountDownLatch(1);

            doAnswer(inv -> {
                streamBlocked.countDown();
                boolean released = releaseStream.await(5, TimeUnit.SECONDS);
                if (!released) throw new IllegalStateException("timeout");
                StreamingCallback cb = inv.getArgument(1);
                cb.onComplete(new LlmResponse("done", 10L, 1, 1_000_000L));
                return null;
            }).when(llm).askStreaming(any(), any());

            session.send("hello");
            assertThat(streamBlocked.await(2, TimeUnit.SECONDS)).isTrue();

            when(config.serverName("srv-1")).thenReturn("Server 1");
            session.switchModel("model-1");
            session.switchServer("srv-1");

            awaitEvent("modelChanged:model-1");
            awaitEvent("notice:SUCCESS");

            verify(llm, never()).rebuildModel();

            releaseStream.countDown();
            awaitIdle();

            verify(llm, times(1)).rebuildModel();
        }

        @Test
        void cancelAfterSwitchModelCallsRebuildOnce() throws Exception {
            CountDownLatch streamBlocked = new CountDownLatch(1);

            doAnswer(_ -> {
                streamBlocked.countDown();
                Thread.sleep(2000);
                return null;
            }).when(llm).askStreaming(any(), any());

            session.send("hello");
            assertThat(streamBlocked.await(2, TimeUnit.SECONDS)).isTrue();

            session.switchModel("new-model");
            awaitEvent("modelChanged:new-model");
            verify(llm, never()).rebuildModel();

            session.cancelCurrentRequest();
            verify(llm, times(1)).rebuildModel();
        }

        @Test
        void secondCancelIsNoop() throws Exception {
            CountDownLatch streamBlocked = new CountDownLatch(1);

            doAnswer(_ -> {
                streamBlocked.countDown();
                Thread.sleep(2000);
                return null;
            }).when(llm).askStreaming(any(), any());

            session.send("hello");
            assertThat(streamBlocked.await(2, TimeUnit.SECONDS)).isTrue();

            session.cancelCurrentRequest();
            session.cancelCurrentRequest();

            assertThat(session.getHistory())
                    .extracting(AIChatMessage::text)
                    .containsOnlyOnce("Generation cancelled.");
        }

        @Test
        void loadModelsDoesNotBlockDuringStreaming() throws Exception {
            CountDownLatch streamBlocked = new CountDownLatch(1);
            CountDownLatch releaseStream = new CountDownLatch(1);

            doAnswer(_ -> {
                streamBlocked.countDown();
                boolean released = releaseStream.await(5, TimeUnit.SECONDS);
                if (!released) {
                    throw new IllegalStateException("test did not release the worker");
                }
                return null;
            }).when(llm).askStreaming(any(), any());

            session.send("hello");
            assertThat(streamBlocked.await(2, TimeUnit.SECONDS)).isTrue();

            when(llm.listModels()).thenReturn(List.of("model-1", "model-2"));
            session.loadModels();

            awaitEvent("modelsLoaded");

            releaseStream.countDown();
            awaitIdle();
        }

        @Test
        void switchModelDoesNotBlockDuringStreaming() throws Exception {
            CountDownLatch streamBlocked = new CountDownLatch(1);
            CountDownLatch releaseStream = new CountDownLatch(1);

            doAnswer(_ -> {
                streamBlocked.countDown();
                boolean released = releaseStream.await(5, TimeUnit.SECONDS);
                if (!released) {
                    throw new IllegalStateException("test did not release the worker");
                }
                return null;
            }).when(llm).askStreaming(any(), any());

            session.send("hello");
            assertThat(streamBlocked.await(2, TimeUnit.SECONDS)).isTrue();

            session.switchModel("new-model");

            awaitEvent("modelChanged:new-model");

            releaseStream.countDown();
            awaitIdle();
        }

        @Test
        void deferredRebuildModel() throws Exception {
            CountDownLatch streamBlocked = new CountDownLatch(1);
            CountDownLatch releaseStream = new CountDownLatch(1);

            doAnswer(inv -> {
                streamBlocked.countDown();
                boolean released = releaseStream.await(5, TimeUnit.SECONDS);
                if (!released) {
                    throw new IllegalStateException("test did not release the worker");
                }
                StreamingCallback cb = inv.getArgument(1);
                cb.onComplete(new LlmResponse("done", 10L, 1, 1_000_000L));
                return null;
            }).when(llm).askStreaming(any(), any());

            session.send("hello");
            assertThat(streamBlocked.await(2, TimeUnit.SECONDS)).isTrue();

            when(config.serverName("srv-1")).thenReturn("Server 1");
            session.switchServer("srv-1");
            awaitEvent("notice:SUCCESS");

            verify(llm, never()).rebuildModel();

            releaseStream.countDown();
            awaitIdle();

            verify(llm, times(1)).rebuildModel();
        }

        @Test
        void cancelAppliesDeferredRebuild() throws Exception {
            CountDownLatch streamBlocked = new CountDownLatch(1);

            doAnswer(_ -> {
                streamBlocked.countDown();
                Thread.sleep(2000);
                return null;
            }).when(llm).askStreaming(any(), any());

            session.send("hello");
            assertThat(streamBlocked.await(2, TimeUnit.SECONDS)).isTrue();

            when(config.serverName("srv-2")).thenReturn("Server 2");
            session.switchServer("srv-2");
            awaitEvent("notice:SUCCESS");

            verify(llm, never()).rebuildModel();

            session.cancelCurrentRequest();

            verify(llm, times(1)).rebuildModel();
        }

        @Test
        void shutdownWhileStreaming() throws Exception {
            CountDownLatch streamBlocked = new CountDownLatch(1);

            doAnswer(_ -> {
                streamBlocked.countDown();
                Thread.sleep(5000);
                return null;
            }).when(llm).askStreaming(any(), any());

            session.send("hello");
            assertThat(streamBlocked.await(2, TimeUnit.SECONDS)).isTrue();

            long start = System.currentTimeMillis();
            session.shutdown();
            long duration = System.currentTimeMillis() - start;

            assertThat(duration).isLessThan(3000);
        }

        @Test
        void cancelDuringSubmissionCancelsQueuedRequest() throws Exception {
            ControlledExecutorService executor = new ControlledExecutorService();

            session.shutdown();
            session = new ChatSession(llm, config, executor);
            session.addListener(listener);

            AtomicReference<Throwable> senderFailure = new AtomicReference<>();
            Thread sender = new Thread(() -> {
                try {
                    session.send("hello");
                } catch (Throwable error) {
                    senderFailure.set(error);
                }
            });

            sender.start();

            try {
                assertThat(executor.awaitExecuteEntered()).isTrue();

                session.cancelCurrentRequest();

                executor.releaseExecute();

                sender.join(TimeUnit.SECONDS.toMillis(2));

                assertThat(sender.isAlive()).isFalse();
                assertThat(senderFailure.get()).isNull();

                executor.runSubmittedTask();

                verify(llm, never()).askStreaming(any(), any());
            } finally {
                executor.releaseExecute();
                sender.join(TimeUnit.SECONDS.toMillis(2));
                session.shutdown();
            }
        }

        private static final class ControlledExecutorService extends AbstractExecutorService {

            private final CountDownLatch executeEntered = new CountDownLatch(1);
            private final CountDownLatch allowExecuteReturn = new CountDownLatch(1);
            private final AtomicReference<Runnable> submittedTask = new AtomicReference<>();

            private volatile boolean shutdown;

            @Override
            public void execute(@NonNull Runnable command) {
                submittedTask.set(command);
                executeEntered.countDown();

                try {
                    if (!allowExecuteReturn.await(2, TimeUnit.SECONDS)) {
                        throw new RejectedExecutionException("Test timeout");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RejectedExecutionException(e);
                }
            }

            boolean awaitExecuteEntered() throws InterruptedException {
                return executeEntered.await(2, TimeUnit.SECONDS);
            }

            void releaseExecute() {
                allowExecuteReturn.countDown();
            }

            void runSubmittedTask() {
                Runnable task = submittedTask.get();
                assertThat(task).isNotNull();
                task.run();
            }

            @Override
            public void shutdown() {
                shutdown = true;
                releaseExecute();
            }

            @Override
            public @NonNull List<Runnable> shutdownNow() {
                shutdown = true;
                releaseExecute();
                return List.of();
            }

            @Override
            public boolean isShutdown() {
                return shutdown;
            }

            @Override
            public boolean isTerminated() {
                return shutdown;
            }

            @Override
            public boolean awaitTermination(long timeout, @NonNull TimeUnit unit) {
                return shutdown;
            }
        }
    }
}