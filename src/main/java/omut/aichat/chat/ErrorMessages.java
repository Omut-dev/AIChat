
package omut.aichat.chat;

import dev.langchain4j.exception.InternalServerException;

import javax.net.ssl.SSLException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/**
 * Turns transport and provider exceptions into short messages
 * suitable for display in the transcript.
 */
public final class ErrorMessages {

    private static final String CONNECTION_ERROR =
            "Cannot connect to Ollama. Is it running?";
    private static final String TIMEOUT_ERROR =
            "Request timed out. The model may be loading, try again.";
    private static final String MODEL_NOT_FOUND =
            "Model not found. Check that the model is pulled via 'ollama list'.";

    private static final String RAM_ERROR =
            "Not enough RAM to load the model. Try a smaller model (e.g. gemma2:2b) or close other apps.";
    private static final String GPU_MEMORY_ERROR =
            "Not enough GPU memory. Try a smaller model or switch to CPU (set OLLAMA_NUM_GPU=0).";
    private static final String NO_GPU_ERROR =
            "No compatible GPU found. Falling back to CPU (may be slow).";
    private static final String CONTEXT_ERROR =
            "Conversation is too long. Clear some messages or reduce the history limit in Settings.";
    private static final String MODEL_BUSY =
            "Model is busy with another request. Try again in a moment.";
    private static final String MODEL_LOAD_ERROR =
            "Model failed to load. Check ollama list and the ollama serve logs.";
    private static final String UNSUPPORTED_MODEL =
            "This model format is not supported by your Ollama version.";
    private static final String DISK_SPACE_ERROR =
            "Not enough disk space to pull the model.";
    private static final String DNS_ERROR =
            "Cannot resolve the server hostname. Check the URL in Settings.";
    private static final String SSL_ERROR =
            "SSL error connecting to the server. Check the URL and certificates.";
    private static final String SERVER_ERROR =
            "Ollama returned a server error. Check the ollama serve logs.";

    private ErrorMessages() {}

    /**
     * Returns a one-line explanation of {@code ex} for the user.
     * Never returns {@code null}.
     */
    public static String humanize(Throwable ex) {
        if (ex == null) return "Unknown error.";

        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());

        Throwable deepest = ex;
        StringBuilder messages = new StringBuilder();

        for (Throwable current = ex;
             current != null && visited.add(current);
             current = current.getCause()) {

            deepest = current;

            if (current instanceof ConnectException
                    || current instanceof NoRouteToHostException) {
                return CONNECTION_ERROR;
            }

            if (current instanceof SocketTimeoutException
                    || current instanceof TimeoutException) {
                return TIMEOUT_ERROR;
            }

            switch (current) {
                case UnknownHostException _ -> {
                    return DNS_ERROR;
                }
                case SSLException _ -> {
                    return SSL_ERROR;
                }
                case InternalServerException _ -> {
                    return SERVER_ERROR;
                }
                default -> {
                }
            }

            if (current.getMessage() != null) {
                if (!messages.isEmpty()) {
                    messages.append(' ');
                }
                messages.append(current.getMessage());
            }
        }

        String msg = messages.toString().toLowerCase(Locale.ROOT);

        if (containsAny(msg,
                "cuda out of memory",
                "out of vram")) {
            return GPU_MEMORY_ERROR;
        }

        if (containsAny(msg,
                "out of memory",
                "cannot allocate memory",
                "mmap failed")) {
            return RAM_ERROR;
        }

        if (containsAny(msg,
                "no cuda gpu",
                "no compatible gpu")) {
            return NO_GPU_ERROR;
        }

        if (containsAny(msg,
                "context length",
                "too many tokens",
                "exceeds context window")) {
            return CONTEXT_ERROR;
        }

        if (containsAny(msg,
                "model is busy",
                "already processing",
                "concurrent request")) {
            return MODEL_BUSY;
        }

        if (containsAny(msg,
                "failed to load model",
                "error loading model",
                "could not load model")) {
            return MODEL_LOAD_ERROR;
        }

        if (containsAny(msg,
                "unsupported model",
                "invalid model format")) {
            return UNSUPPORTED_MODEL;
        }

        if (containsAny(msg,
                "no space left",
                "disk full",
                "enospc")) {
            return DISK_SPACE_ERROR;
        }

        if (containsAny(msg, "pkix")) {
            return SSL_ERROR;
        }

        if (containsHttpServerError(msg)) {
            return SERVER_ERROR;
        }

        if (msg.contains("model") && msg.contains("not found")) {
            return MODEL_NOT_FOUND;
        }

        String fallback = deepest.getMessage();
        return fallback != null ? fallback : deepest.getClass().getSimpleName();
    }

    private static boolean containsHttpServerError(String message) {
        return containsAny(message,
                "http 500",
                "http 502",
                "http 503",
                "http/1.0 500",
                "http/1.0 502",
                "http/1.0 503",
                "http/1.1 500",
                "http/1.1 502",
                "http/1.1 503",
                "http status 500",
                "http status 502",
                "http status 503",
                "status 500",
                "status 502",
                "status 503",
                "status: 500",
                "status: 502",
                "status: 503",
                "status code 500",
                "status code 502",
                "status code 503");
    }

    private static boolean containsAny(String message, String... phrases) {
        for (String phrase : phrases) {
            if (message.contains(phrase)) {
                return true;
            }
        }
        return false;
    }
}
