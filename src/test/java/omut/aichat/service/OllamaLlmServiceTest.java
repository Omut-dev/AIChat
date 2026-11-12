package omut.aichat.service;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import omut.aichat.chat.AIChatMessage;
import omut.aichat.chat.LlmResponse;
import omut.aichat.chat.StreamingCallback;
import omut.aichat.config.AppConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class OllamaLlmServiceTest {

    /**
     * Minimal stub HTTP server: for a given path, returns a fixed status + body.
     * Enough for OllamaLlmService which only does GET /api/tags.
     */
    private static final class StubHttpServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "stub-http");
            t.setDaemon(true);
            return t;
        });
        private final Map<String, Response> routes = new ConcurrentHashMap<>();
        private volatile boolean running = true;

        record Response(int status, String body) {}

        StubHttpServer() throws IOException {
            this.serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            executor.submit(this::acceptLoop);
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + port();
        }

        void route(String path, int status, String body) {
            routes.put(path, new Response(status, body));
        }

        void route(String path, int status) {
            route(path, status, "");
        }

        private void acceptLoop() {
            while (running) {
                try {
                    Socket socket = serverSocket.accept();
                    executor.submit(() -> handle(socket));
                } catch (IOException e) {
                    if (running) {
                        // unexpected, but don't kill the loop
                    }
                }
            }
        }

        private void handle(Socket socket) {
            try (socket) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                String requestLine = reader.readLine();
                if (requestLine == null) {
                    return;
                }
                // Drain headers
                String line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    // ignore
                }

                String path = requestLine.split(" ")[1];
                // strip query string if any
                int q = path.indexOf('?');
                if (q >= 0) {
                    path = path.substring(0, q);
                }

                Response resp = routes.get(path);
                if (resp == null) {
                    resp = new Response(404, "");
                }

                byte[] bodyBytes = resp.body().getBytes(StandardCharsets.UTF_8);
                String statusLine = "HTTP/1.1 " + resp.status() + " " + reason(resp.status()) + "\r\n";
                String headers =
                        "Content-Type: application/json\r\n" +
                                "Content-Length: " + bodyBytes.length + "\r\n" +
                                "Connection: close\r\n" +
                                "\r\n";

                OutputStream out = socket.getOutputStream();
                out.write(statusLine.getBytes(StandardCharsets.US_ASCII));
                out.write(headers.getBytes(StandardCharsets.US_ASCII));
                out.write(bodyBytes);
                out.flush();
            } catch (IOException ignored) {
                // client disconnected
            }
        }

        private static String reason(int status) {
            return switch (status) {
                case 200 -> "OK";
                case 404 -> "Not Found";
                case 500 -> "Internal Server Error";
                default -> "Status";
            };
        }

        @Override
        public void close() {
            running = false;
            try {
                serverSocket.close();
            } catch (IOException ignored) {
                // ignore
            }
            executor.shutdownNow();
        }
    }

    private StubHttpServer server;
    private AppConfig config;

    @BeforeEach
    void setUp() throws IOException {
        server = new StubHttpServer();

        config = mock(AppConfig.class);
        when(config.getBaseUrl()).thenReturn(server.baseUrl());
        when(config.requestTimeoutMinutes()).thenReturn(5);
        when(config.temperature()).thenReturn(0.5);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    @Nested
    @DisplayName("isAvailable")
    class IsAvailable {

        @Test
        @DisplayName("Returns true when /api/tags responds 200")
        void ok() {
            respondWith(200);
            OllamaLlmService service = new OllamaLlmService(config, "qwen2.5:7b");

            assertThat(service.isAvailable()).isTrue();
        }

        @Test
        @DisplayName("Returns false when /api/tags responds 500")
        void serverError() {
            respondWith(500);
            OllamaLlmService service = new OllamaLlmService(config, "qwen2.5:7b");

            assertThat(service.isAvailable()).isFalse();
        }

        @Test
        @DisplayName("Returns false when /api/tags responds 404")
        void notFound() {
            respondWith(404);
            OllamaLlmService service = new OllamaLlmService(config, "qwen2.5:7b");

            assertThat(service.isAvailable()).isFalse();
        }

        @Test
        @DisplayName("Returns false when the server is not listening")
        void connectionRefused() {
            int port = server.port();
            server.close();
            server = null;

            when(config.getBaseUrl()).thenReturn("http://127.0.0.1:" + port);
            OllamaLlmService service = new OllamaLlmService(config, "qwen2.5:7b");

            assertThat(service.isAvailable()).isFalse();
        }
    }

    @Nested
    @DisplayName("listModels")
    class ListModels {

        @Test
        @DisplayName("Parses model names from /api/tags response")
        void parsesNames() {
            server.route("/api/tags", 200, """
                    {
                      "models": [
                        {"name": "qwen2.5:7b"},
                        {"name": "llama3.1:8b"},
                        {"name": "gemma2:2b"}
                      ]
                    }
                    """);

            OllamaLlmService service = new OllamaLlmService(config, "qwen2.5:7b");

            assertThat(service.listModels())
                    .containsExactly("qwen2.5:7b", "llama3.1:8b", "gemma2:2b");
        }

        @Test
        @DisplayName("Returns empty list when models array is empty")
        void emptyModels() {
            server.route("/api/tags", 200, "{\"models\":[]}");

            OllamaLlmService service = new OllamaLlmService(config, "qwen2.5:7b");

            assertThat(service.listModels()).isEmpty();
        }

        @Test
        @DisplayName("Returns empty list on server error")
        void serverError() {
            respondWith(500);
            OllamaLlmService service = new OllamaLlmService(config, "qwen2.5:7b");

            assertThat(service.listModels()).isEmpty();
        }

        @Test
        @DisplayName("Returns empty list on malformed JSON")
        void malformedJson() {
            server.route("/api/tags", 200, "not json");

            OllamaLlmService service = new OllamaLlmService(config, "qwen2.5:7b");

            assertThat(service.listModels()).isEmpty();
        }

        @Test
        @DisplayName("Returns empty list when connection refused")
        void connectionRefused() {
            int port = server.port();
            server.close();
            server = null;

            when(config.getBaseUrl()).thenReturn("http://127.0.0.1:" + port);
            OllamaLlmService service = new OllamaLlmService(config, "qwen2.5:7b");

            assertThat(service.listModels()).isEmpty();
        }
    }

    @Nested
    @DisplayName("switchModel")
    class SwitchModel {

        @Test
        @DisplayName("Sets current model and returns it")
        void valid() {
            OllamaLlmService service = new OllamaLlmService(config, "qwen2.5:7b");

            service.switchModel("llama3.1:8b");

            assertThat(service.currentModel()).isEqualTo("llama3.1:8b");
        }

        @Test
        @DisplayName("Throws on null model name")
        void nullRejected() {
            OllamaLlmService service = new OllamaLlmService(config, "qwen2.5:7b");

            assertThatThrownBy(() -> service.switchModel(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("blank");
        }

        @Test
        @DisplayName("Throws on blank model name")
        void blankRejected() {
            OllamaLlmService service = new OllamaLlmService(config, "qwen2.5:7b");

            assertThatThrownBy(() -> service.switchModel("   "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("blank");
        }
    }

    @Nested
    @DisplayName("rebuildModel")
    class RebuildModel {

        @Test
        @DisplayName("Keeps the current model after rebuild")
        void keepsModel() {
            OllamaLlmService service = new OllamaLlmService(config, "qwen2.5:7b");

            service.rebuildModel();

            assertThat(service.currentModel()).isEqualTo("qwen2.5:7b");
        }

        @Test
        @DisplayName("Uses the latest config values on rebuild")
        void usesLatestConfig() {
            OllamaLlmService service = new OllamaLlmService(config, "qwen2.5:7b");

            when(config.temperature()).thenReturn(1.5);
            when(config.requestTimeoutMinutes()).thenReturn(30);

            service.rebuildModel();

            assertThat(service.currentModel()).isEqualTo("qwen2.5:7b");

            verify(config, atLeast(2)).temperature();
            verify(config, atLeast(2)).requestTimeoutMinutes();
        }
    }

    @Nested
    @DisplayName("askStreaming")
    class AskStreaming {

        private OllamaLlmService serviceWithStream(String... chunks) {
            StreamingChatModel model = mock(StreamingChatModel.class);

            doAnswer(inv -> {
                StreamingChatResponseHandler handler = inv.getArgument(1);
                for (String chunk : chunks) {
                    handler.onPartialResponse(chunk);
                }
                ChatResponse response = ChatResponse.builder()
                        .aiMessage(AiMessage.from(String.join("", chunks)))
                        .build();
                handler.onCompleteResponse(response);
                return null;
            }).when(model).chat(anyList(), any());

            return new OllamaLlmService(config, "qwen2.5:7b", name -> model);
        }

        private OllamaLlmService serviceWithError(Throwable error) {
            StreamingChatModel model = mock(StreamingChatModel.class);

            doAnswer(inv -> {
                StreamingChatResponseHandler handler = inv.getArgument(1);
                handler.onError(error);
                return null;
            }).when(model).chat(anyList(), any());

            return new OllamaLlmService(config, "qwen2.5:7b", name -> model);
        }

        @Test
        @DisplayName("Accumulates chunks and delivers onComplete with text")
        void happyPath() {
            OllamaLlmService service = serviceWithStream("Hello", " ", "world");

            List<AIChatMessage> history = List.of(AIChatMessage.user("hi"));
            AtomicReference<LlmResponse> result = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);

            service.askStreaming(history, new StreamingCallback() {
                @Override public void onToken(String chunk, String fullText) {}
                @Override public void onComplete(LlmResponse response) {
                    result.set(response);
                    latch.countDown();
                }
                @Override public void onError(Throwable error) {
                    latch.countDown();
                }
            });

            assertThat(await(latch)).isTrue();
            assertThat(result.get()).isNotNull();
            assertThat(result.get().text()).isEqualTo("Hello world");
        }

        @Test
        @DisplayName("Emits onToken for every chunk with growing fullText")
        void tokenAccumulation() {
            OllamaLlmService service = serviceWithStream("A", "B", "C");

            List<AIChatMessage> history = List.of(AIChatMessage.user("hi"));
            List<String> chunks = new CopyOnWriteArrayList<>();
            List<String> fullTexts = new CopyOnWriteArrayList<>();
            CountDownLatch latch = new CountDownLatch(1);

            service.askStreaming(history, new StreamingCallback() {
                @Override public void onToken(String chunk, String fullText) {
                    chunks.add(chunk);
                    fullTexts.add(fullText);
                }
                @Override public void onComplete(LlmResponse response) { latch.countDown(); }
                @Override public void onError(Throwable error) { latch.countDown(); }
            });

            assertThat(await(latch)).isTrue();
            assertThat(chunks).containsExactly("A", "B", "C");
            assertThat(fullTexts).containsExactly("A", "AB", "ABC");
        }

        @Test
        @DisplayName("Propagates onError from the model")
        void error() {
            OllamaLlmService service = serviceWithError(new RuntimeException("boom"));

            List<AIChatMessage> history = List.of(AIChatMessage.user("hi"));
            AtomicReference<Throwable> captured = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);

            service.askStreaming(history, new StreamingCallback() {
                @Override public void onToken(String chunk, String fullText) {}
                @Override public void onComplete(LlmResponse response) { latch.countDown(); }
                @Override public void onError(Throwable error) {
                    captured.set(error);
                    latch.countDown();
                }
            });

            assertThat(await(latch)).isTrue();
            assertThat(captured.get()).isNotNull();
            assertThat(captured.get()).hasMessage("boom");
        }

        private boolean await(CountDownLatch latch) {
            try {
                return latch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private void respondWith(int status) {
        server.route("/api/tags", status);
    }
}