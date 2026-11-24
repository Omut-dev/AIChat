
package omut.aichat.chat;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InternalServerException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.net.ssl.SSLException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ErrorMessagesTest {

    @Test
    @DisplayName("null → \"Unknown error.\"")
    void nullThrowable() {
        assertThat(ErrorMessages.humanize(null)).isEqualTo("Unknown error.");
    }

    @Nested
    @DisplayName("Connection failures")
    class ConnectionFailures {

        @ParameterizedTest(name = "{0}")
        @MethodSource("exceptions")
        @DisplayName("→ connection message")
        void direct(Throwable ex) {
            assertThat(ErrorMessages.humanize(ex))
                    .isEqualTo("Cannot connect to Ollama. Is it running?");
        }

        static Stream<Throwable> exceptions() {
            return Stream.of(
                    new ConnectException("Connection refused"),
                    new NoRouteToHostException("No route to host"),
                    new ConnectException()
            );
        }
    }

    @Nested
    @DisplayName("Timeouts")
    class Timeouts {

        @ParameterizedTest(name = "{0}")
        @MethodSource("exceptions")
        @DisplayName("→ timeout message")
        void direct(Throwable ex) {
            assertThat(ErrorMessages.humanize(ex))
                    .startsWith("Request timed out.");
        }

        static Stream<Throwable> exceptions() {
            return Stream.of(
                    new SocketTimeoutException("Read timed out"),
                    new TimeoutException("Took too long")
            );
        }
    }

    @Nested
    @DisplayName("Wrapped exceptions")
    class Wrapped {

        @Test
        @DisplayName("ConnectException inside RuntimeException → connection message")
        void wrappedConnection() {
            Throwable ex = new RuntimeException("outer",
                    new RuntimeException("middle",
                            new ConnectException("refused")));

            assertThat(ErrorMessages.humanize(ex))
                    .isEqualTo("Cannot connect to Ollama. Is it running?");
        }

        @Test
        @DisplayName("TimeoutException inside RuntimeException → timeout message")
        void wrappedTimeout() {
            Throwable ex = new RuntimeException("outer", new TimeoutException());

            assertThat(ErrorMessages.humanize(ex))
                    .startsWith("Request timed out.");
        }

        @Test
        @DisplayName("Self-referencing exception does not loop forever")
        void selfReference() {
            RuntimeException self = new RuntimeException("loop") {
                @Override
                public synchronized Throwable getCause() {
                    return this;
                }
            };

            assertThat(ErrorMessages.humanize(self)).isEqualTo("loop");
        }

        @Test
        @DisplayName("Circular cause chain does not loop forever")
        void circularReference() {
            RuntimeException first = new RuntimeException("first");
            RuntimeException second = new RuntimeException("second");

            first.initCause(second);

            RuntimeException circular = new RuntimeException("outer") {
                @Override
                public synchronized Throwable getCause() {
                    return first;
                }
            };

            second.initCause(circular);

            assertThat(ErrorMessages.humanize(circular)).isEqualTo("second");
        }

        @Test
        @DisplayName("UnknownHostException inside RuntimeException → DNS message")
        void wrappedUnknownHost() {
            Throwable ex = new RuntimeException("Connection failed",
                    new UnknownHostException("ollama.example.com"));

            assertThat(ErrorMessages.humanize(ex))
                    .isEqualTo("Cannot resolve the server hostname. Check the URL in Settings.");
        }

        @Test
        @DisplayName("SSLException inside RuntimeException → SSL message")
        void wrappedSsl() {
            Throwable ex = new RuntimeException("Connection failed",
                    new SSLException("certificate error"));

            assertThat(ErrorMessages.humanize(ex))
                    .isEqualTo("SSL error connecting to the server. Check the URL and certificates.");
        }
    }

    @Nested
    @DisplayName("Out of memory")
    class OutOfMemory {

        @ParameterizedTest(name = "{0}")
        @MethodSource("messages")
        void ramErrors(String message) {
            assertThat(ErrorMessages.humanize(new RuntimeException(message)))
                    .isEqualTo("Not enough RAM to load the model. Try a smaller model (e.g. gemma2:2b) or close other apps.");
        }

        static Stream<String> messages() {
            return Stream.of(
                    "Out of memory",
                    "Cannot allocate memory",
                    "mmap failed"
            );
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("gpuMessages")
        void gpuMemoryErrors(String message) {
            assertThat(ErrorMessages.humanize(new RuntimeException(message)))
                    .isEqualTo("Not enough GPU memory. Try a smaller model or switch to CPU (set OLLAMA_NUM_GPU=0).");
        }

        static Stream<String> gpuMessages() {
            return Stream.of(
                    "CUDA out of memory",
                    "Out of VRAM"
            );
        }
    }

    @Nested
    @DisplayName("GPU availability")
    class GpuAvailability {

        @ParameterizedTest(name = "{0}")
        @MethodSource("messages")
        void noCompatibleGpu(String message) {
            assertThat(ErrorMessages.humanize(new RuntimeException(message)))
                    .isEqualTo("No compatible GPU found. Falling back to CPU (may be slow).");
        }

        static Stream<String> messages() {
            return Stream.of(
                    "No CUDA GPU",
                    "No compatible GPU"
            );
        }
    }

    @Nested
    @DisplayName("Context length")
    class ContextLength {

        @ParameterizedTest(name = "{0}")
        @MethodSource("messages")
        void contextErrors(String message) {
            assertThat(ErrorMessages.humanize(new RuntimeException(message)))
                    .isEqualTo("Conversation is too long. Clear some messages or reduce the history limit in Settings.");
        }

        static Stream<String> messages() {
            return Stream.of(
                    "Context length exceeded",
                    "Too many tokens",
                    "Input exceeds context window"
            );
        }
    }

    @Nested
    @DisplayName("Model busy")
    class ModelBusy {

        @ParameterizedTest(name = "{0}")
        @MethodSource("messages")
        void busyErrors(String message) {
            assertThat(ErrorMessages.humanize(new RuntimeException(message)))
                    .isEqualTo("Model is busy with another request. Try again in a moment.");
        }

        static Stream<String> messages() {
            return Stream.of(
                    "Model is busy",
                    "Already processing",
                    "Concurrent request"
            );
        }
    }

    @Nested
    @DisplayName("Model loading")
    class ModelLoading {

        @ParameterizedTest(name = "{0}")
        @MethodSource("messages")
        void loadingErrors(String message) {
            assertThat(ErrorMessages.humanize(new RuntimeException(message)))
                    .isEqualTo("Model failed to load. Check ollama list and the ollama serve logs.");
        }

        static Stream<String> messages() {
            return Stream.of(
                    "Failed to load model",
                    "Error loading model",
                    "Could not load model"
            );
        }
    }

    @Nested
    @DisplayName("Unsupported model")
    class UnsupportedModel {

        @ParameterizedTest(name = "{0}")
        @MethodSource("messages")
        void unsupportedErrors(String message) {
            assertThat(ErrorMessages.humanize(new RuntimeException(message)))
                    .isEqualTo("This model format is not supported by your Ollama version.");
        }

        static Stream<String> messages() {
            return Stream.of(
                    "Unsupported model",
                    "Invalid model format"
            );
        }
    }

    @Nested
    @DisplayName("Disk space")
    class DiskSpace {

        @ParameterizedTest(name = "{0}")
        @MethodSource("messages")
        void diskErrors(String message) {
            assertThat(ErrorMessages.humanize(new RuntimeException(message)))
                    .isEqualTo("Not enough disk space to pull the model.");
        }

        static Stream<String> messages() {
            return Stream.of(
                    "No space left on device",
                    "Disk full",
                    "ENOSPC"
            );
        }
    }

    @Nested
    @DisplayName("DNS failures")
    class DnsFailures {

        @Test
        @DisplayName("UnknownHostException → DNS message")
        void unknownHost() {
            assertThat(ErrorMessages.humanize(new UnknownHostException("ollama.local")))
                    .isEqualTo("Cannot resolve the server hostname. Check the URL in Settings.");
        }
    }

    @Nested
    @DisplayName("SSL failures")
    class SslFailures {

        @Test
        @DisplayName("SSLException → SSL message")
        void sslException() {
            assertThat(ErrorMessages.humanize(new SSLException("handshake failed")))
                    .isEqualTo("SSL error connecting to the server. Check the URL and certificates.");
        }

        @Test
        @DisplayName("PKIX message → SSL message")
        void pkix() {
            assertThat(ErrorMessages.humanize(new RuntimeException("PKIX path building failed")))
                    .isEqualTo("SSL error connecting to the server. Check the URL and certificates.");
        }
    }

    @Nested
    @DisplayName("HTTP server errors")
    class HttpServerErrors {

        @ParameterizedTest(name = "{0}")
        @MethodSource("messages")
        void serverErrors(String message) {
            assertThat(ErrorMessages.humanize(new RuntimeException(message)))
                    .isEqualTo("Ollama returned a server error. Check the ollama serve logs.");
        }

        static Stream<String> messages() {
            return Stream.of(
                    "HTTP 500",
                    "HTTP 502",
                    "HTTP 503"
            );
        }
    }

    @Nested
    @DisplayName("Model not found")
    class ModelNotFound {

        @Test
        @DisplayName("\"model not found\" in message → dedicated message")
        void lowercase() {
            Throwable ex = new RuntimeException("model qwen2 not found");

            assertThat(ErrorMessages.humanize(ex))
                    .startsWith("Model not found.");
        }

        @Test
        @DisplayName("Both keywords required — \"model\" alone is not enough")
        void onlyModel() {
            Throwable ex = new RuntimeException("selected model is big");

            assertThat(ErrorMessages.humanize(ex))
                    .isEqualTo("selected model is big");
        }

        @Test
        @DisplayName("Both keywords required — \"not found\" alone is not enough")
        void onlyNotFound() {
            Throwable ex = new RuntimeException("file not found");

            assertThat(ErrorMessages.humanize(ex))
                    .isEqualTo("file not found");
        }
    }

    @Nested
    @DisplayName("Fall-through")
    class FallThrough {

        @Test
        @DisplayName("Generic exception with message → its message")
        void genericWithMessage() {
            assertThat(ErrorMessages.humanize(new RuntimeException("boom")))
                    .isEqualTo("boom");
        }

        @Test
        @DisplayName("Generic exception without message → simple class name")
        void genericWithoutMessage() {
            assertThat(ErrorMessages.humanize(new RuntimeException()))
                    .isEqualTo("RuntimeException");
        }

        @Test
        @DisplayName("Custom exception class name shows up when message is null")
        void customClass() {
            assertThat(ErrorMessages.humanize(new IllegalStateException()))
                    .isEqualTo("IllegalStateException");
        }
    }

    @Test
    void internalServerExceptionReturnsServerErrorMessage() {
        assertEquals(
                "Ollama returned a server error. Check the ollama serve logs.",
                ErrorMessages.humanize(new InternalServerException("Internal server error"))
        );
    }

    @Test
    void wrappedInternalServerExceptionReturnsServerErrorMessage() {
        assertEquals(
                "Ollama returned a server error. Check the ollama serve logs.",
                ErrorMessages.humanize(
                        new RuntimeException("Request failed",
                                new InternalServerException(
                                        new HttpException(502, "Bad Gateway")
                                )
                        )
                )
        );
    }
}
