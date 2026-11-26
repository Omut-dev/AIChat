package omut.aichat.chat;

import omut.aichat.config.AppConfig;
import omut.aichat.service.LlmService;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatSessionCancelConnectionTest {

    @Test
    void cancelCurrentRequest_shouldIgnoreCallbacksFromCancelledRequest()
            throws Exception {

        LlmService llmService = mock(LlmService.class);
        AppConfig config = mock(AppConfig.class);
        ChatListener listener = mock(ChatListener.class);

        when(config.systemPrompt()).thenReturn("");
        when(config.historyMaxMessages()).thenReturn(100);

        CountDownLatch callbackCaptured = new CountDownLatch(1);
        CountDownLatch releaseStreaming = new CountDownLatch(1);

        AtomicReference<StreamingCallback> capturedCallback =
                new AtomicReference<>();

        doAnswer(invocation -> {
            StreamingCallback callback = invocation.getArgument(1);
            capturedCallback.set(callback);
            callbackCaptured.countDown();

            try {
                releaseStreaming.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            return null;
        }).when(llmService).askStreaming(
                anyList(),
                any(StreamingCallback.class)
        );

        ChatSession session = new ChatSession(llmService, config);
        session.addListener(listener);

        try {
            session.send("test");

            assertTrue(
                    callbackCaptured.await(1, TimeUnit.SECONDS),
                    "Streaming callback should be registered"
            );

            StreamingCallback callback = capturedCallback.get();
            assertNotNull(callback);

            callback.onToken("hello", "hello");

            session.cancelCurrentRequest();

            assertEquals(
                    2,
                    session.getHistory().size(),
                    "History should contain the user message and cancellation message"
            );

            assertEquals(
                    "test",
                    session.getHistory().get(0).text()
            );

            assertEquals(
                    "Generation cancelled.",
                    session.getHistory().get(1).text()
            );

            // Ignore listener events caused by cancellation itself.
            clearInvocations(listener);

            // Simulate callbacks arriving after cancellation.
            callback.onToken(" world", "hello world");

            callback.onComplete(
                    new LlmResponse("hello world", 100, 2, 100_000_000)
            );

            callback.onError(
                    new RuntimeException("Late streaming error")
            );

            verifyNoInteractions(listener);

            assertEquals(
                    2,
                    session.getHistory().size(),
                    "Late callbacks must not modify chat history"
            );

            assertEquals(
                    "Generation cancelled.",
                    session.getHistory().get(1).text()
            );

            verify(llmService, never()).currentModel();

        } finally {
            releaseStreaming.countDown();
            session.shutdown();
        }
    }
}
