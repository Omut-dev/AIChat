package omut.aichat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import omut.aichat.chat.AIChatMessage;
import omut.aichat.chat.LlmResponse;
import omut.aichat.chat.StreamingCallback;
import omut.aichat.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

public class OllamaLlmService implements LlmService {

    private static final Logger log = LoggerFactory.getLogger(OllamaLlmService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AppConfig config;
    private final Function<String, StreamingChatModel> modelFactory;
    private final AtomicReference<StreamingRequest> activeRequest = new AtomicReference<>();

    private volatile String currentModel;
    private volatile StreamingChatModel streamingModel;

    public OllamaLlmService(AppConfig config, String initialModel) {
        this(config, initialModel, name -> buildModel(config, name));
    }

    /**
     * Package-private constructor for tests with a mock factory.
     */
    OllamaLlmService(
            AppConfig config,
            String initialModel,
            Function<String, StreamingChatModel> modelFactory
    ) {
        this.config = config;
        this.modelFactory = modelFactory;
        switchModel(initialModel);
    }

    private static StreamingChatModel buildModel(AppConfig config, String modelName) {
        return OllamaStreamingChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .modelName(modelName)
                .timeout(Duration.ofMinutes(config.requestTimeoutMinutes()))
                .temperature(config.temperature())
                .logRequests(true)
                .logResponses(false)
                .build();
    }

    @Override
    public boolean isAvailable() {
        HttpURLConnection connection = null;
        try {
            connection = openTagsConnection(1500);
            return connection.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    @Override
    public List<String> listModels() {
        List<String> result = new ArrayList<>();
        HttpURLConnection connection = null;
        try {
            connection = openTagsConnection(2000);
            JsonNode root = MAPPER.readTree(connection.getInputStream());
            for (JsonNode node : root.get("models")) {
                result.add(node.get("name").asText());
            }
        } catch (Exception e) {
            log.warn("Failed to list models from {}: {}", config.getBaseUrl(), e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        return result;
    }

    @Override
    public void switchModel(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException("Model name must not be blank");
        }
        this.currentModel = modelName;
        this.streamingModel = modelFactory.apply(modelName);
    }

    @Override
    public String currentModel() {
        return currentModel;
    }

    @Override
    public void rebuildModel() {
        if (currentModel != null) {
            switchModel(currentModel);
        }
    }

    @Override
    public void cancelCurrentRequest() {
        StreamingRequest request = activeRequest.get();
        if (request != null) {
            request.cancel();
        }
    }

    @Override
    public void askStreaming(List<AIChatMessage> conversation, StreamingCallback callback) {
        List<ChatMessage> messages = new ArrayList<>();
        for (AIChatMessage msg : conversation) {
            messages.add(toLangchainMessage(msg));
        }

        CountDownLatch latch = new CountDownLatch(1);
        StreamingRequest request = new StreamingRequest(latch);

        if (!activeRequest.compareAndSet(null, request)) {
            callback.onError(new IllegalStateException("Another streaming request is already active"));
            return;
        }

        long start = System.nanoTime();
        StringBuilder accumulator = new StringBuilder();

        try {
            streamingModel.chat(messages, new StreamingChatResponseHandler() {

                @Override
                public void onPartialResponse(String partialResponse) {
                    appendAndNotify(partialResponse);
                }

                @Override
                public void onPartialResponse(
                        PartialResponse partialResponse,
                        PartialResponseContext context) {

                    request.setHandle(context.streamingHandle());
                    appendAndNotify(partialResponse.text());
                }

                private void appendAndNotify(String partialResponse) {
                    if (request.isCancelled()) {
                        return;
                    }

                    accumulator.append(partialResponse);
                    callback.onToken(partialResponse, accumulator.toString());
                }

                @Override
                public void onCompleteResponse(ChatResponse response) {
                    try {
                        long elapsedNanos = System.nanoTime() - start;
                        int outputTokens = response.tokenUsage() != null
                                ? response.tokenUsage().outputTokenCount()
                                : 0;
                        callback.onComplete(new LlmResponse(
                                accumulator.toString(),
                                elapsedNanos / 1_000_000,
                                outputTokens,
                                elapsedNanos
                        ));
                    } finally {
                        latch.countDown();
                    }
                }

                @Override
                public void onError(Throwable error) {
                    try {
                        callback.onError(error);
                    } finally {
                        latch.countDown();
                    }
                }
            });

            int timeoutMinutes = config.requestTimeoutMinutes();
            try {
                if (!latch.await(timeoutMinutes, TimeUnit.MINUTES)) {
                    request.cancel();
                    callback.onError(new TimeoutException(
                            "Streaming did not complete within " + timeoutMinutes + " minutes"));
                }
            } catch (InterruptedException e) {
                request.cancel();
                Thread.currentThread().interrupt();
                callback.onError(e);
            }
        } finally {
            activeRequest.compareAndSet(request, null);
        }
    }

    private ChatMessage toLangchainMessage(AIChatMessage msg) {
        return switch (msg.role()) {
            case USER -> UserMessage.from(msg.text());
            case ASSISTANT -> AiMessage.from(msg.text());
            case SYSTEM -> SystemMessage.from(msg.text());
        };
    }

    private HttpURLConnection openTagsConnection(int timeoutMillis) throws IOException {
        HttpURLConnection connection = (HttpURLConnection)
                URI.create(config.getBaseUrl() + "/api/tags").toURL().openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(timeoutMillis);
        connection.setReadTimeout(timeoutMillis);
        return connection;
    }

    private static class StreamingRequest {

        private final AtomicReference<StreamingHandle> handle = new AtomicReference<>();
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final CountDownLatch completionLatch;

        private StreamingRequest(CountDownLatch completionLatch) {
            this.completionLatch = completionLatch;
        }

        private void setHandle(StreamingHandle streamingHandle) {
            handle.set(streamingHandle);

            if (cancelled.get()) {
                cancelHandle(streamingHandle);
            }
        }

        private void cancel() {
            cancelled.set(true);

            StreamingHandle streamingHandle = handle.get();
            if (streamingHandle != null) {
                cancelHandle(streamingHandle);
            }

            completionLatch.countDown();
        }

        private void cancelHandle(StreamingHandle streamingHandle) {
            try {
                streamingHandle.cancel();
            } catch (RuntimeException e) {
                log.warn("Failed to cancel Ollama streaming request", e);
            }
        }

        private boolean isCancelled() {
            return cancelled.get();
        }
    }
}
