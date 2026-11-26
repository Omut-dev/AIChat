package omut.aichat.service;

import com.sun.net.httpserver.HttpServer;
import omut.aichat.chat.AIChatMessage;
import omut.aichat.config.AppConfig;
import omut.aichat.chat.StreamingCallback;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;

class OllamaLlmServiceCancellationIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(OllamaLlmServiceCancellationIntegrationTest.class);

    @SuppressWarnings("InfiniteLoopStatement")
    @Test
    void cancelCurrentRequestShouldCloseHttpConnection() throws Exception {
        CountDownLatch tokenReceived = new CountDownLatch(1);
        CountDownLatch clientDisconnected = new CountDownLatch(1);

        ExecutorService serverExecutor = Executors.newCachedThreadPool();
        ExecutorService requestExecutor = Executors.newSingleThreadExecutor();

        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0),
                0
        );

        server.setExecutor(serverExecutor);

        String responseChunk = """
                {"model":"test-model","created_at":"2026-01-01T00:00:00Z","message":{"role":"assistant","content":"%s"},"done":false}
                """.formatted("x".repeat(8192));

        byte[] responseBytes = responseChunk.getBytes(StandardCharsets.UTF_8);

        server.createContext("/api/chat", exchange -> {
            try {
                exchange.getResponseHeaders().set(
                        "Content-Type",
                        "application/x-ndjson"
                );
                exchange.sendResponseHeaders(200, 0);
                log.info("Mock server: headers sent, starting streaming");

                var output = exchange.getResponseBody();

                //noinspection InfiniteLoopStatement
                while (true) {
                    output.write(responseBytes);
                    output.flush();
                }
            } catch (IOException e) {
                log.info("Mock server: client disconnected (IOException)");
                clientDisconnected.countDown();
            } finally {
                exchange.close();
                log.info("Mock server: exchange closed");
            }
        });

        server.start();
        log.info("Mock server started on port {}", server.getAddress().getPort());

        try {
            AppConfig config = mock(AppConfig.class);

            when(config.getBaseUrl()).thenReturn(
                    "http://127.0.0.1:" + server.getAddress().getPort()
            );
            when(config.requestTimeoutMinutes()).thenReturn(1);
            when(config.temperature()).thenReturn(0.5);

            OllamaLlmService service = new OllamaLlmService(
                    config,
                    "test-model"
            );
            log.info("Service created");

            StreamingCallback callback = mock(StreamingCallback.class);

            doAnswer(_ -> {
                log.info("Client received first token");
                tokenReceived.countDown();
                return null;
            }).when(callback).onToken(anyString(), anyString());

            Future<?> request = requestExecutor.submit(
                    () -> service.askStreaming(List.of(AIChatMessage.user("Hello")), callback)
            );
            log.info("Streaming request submitted");

            assertTrue(
                    tokenReceived.await(10, TimeUnit.SECONDS),
                    "The server did not deliver a streaming token"
            );
            log.info("First token confirmed, cancelling request");

            service.cancelCurrentRequest();
            request.cancel(true);
            log.info("Cancellation requested");

            assertTrue(
                    clientDisconnected.await(10, TimeUnit.SECONDS),
                    "The HTTP server did not detect client disconnection after cancellation"
            );
            log.info("Test passed: connection closed after cancellation");
        } finally {
            server.stop(0);
            requestExecutor.shutdownNow();
            serverExecutor.shutdownNow();
            log.info("Resources cleaned up");
        }
    }
}