package omut.aichat.service;

import omut.aichat.chat.AIChatMessage;
import omut.aichat.chat.StreamingCallback;

import java.util.List;

public interface LlmService {

    boolean isAvailable();

    List<String> listModels();

    void switchModel(String modelName);

    String currentModel();

    void askStreaming(List<AIChatMessage> conversation, StreamingCallback callback);

    /**
     * Cancels the active streaming request, if any.
     * <p>
     * Implementations that support transport-level cancellation should
     * override this method.
     */
    default void cancelCurrentRequest() {
    }

    /**
     * Rebuilds the underlying streaming model from the current
     * configuration. Call after changing any setting that feeds into
     * the model — base URL, temperature, request timeout.
     */
    void rebuildModel();
}
