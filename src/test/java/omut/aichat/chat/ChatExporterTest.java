package omut.aichat.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatExporterTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tmp;

    private String export(List<AIChatMessage> history) throws IOException {
        Path file = tmp.resolve("chat.md");
        ChatExporter.exportMarkdown(history, file);
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    @Nested
    @DisplayName("Header")
    class Header {

        @Test
        @DisplayName("Starts with title and timestamp")
        void title() throws IOException {
            String md = export(List.of());
            assertThat(md)
                    .startsWith("# AIChat conversation\n\nExported: ")
                    .contains("\n\n---\n\n");
        }
    }

    @Nested
    @DisplayName("USER messages")
    class User {

        @Test
        @DisplayName("Header is '## You', body follows")
        void simple() throws IOException {
            String md = export(List.of(AIChatMessage.user("hello")));
            assertThat(md).contains("## You\n\nhello\n\n");
        }

        @Test
        @DisplayName("Multiline body preserved")
        void multiline() throws IOException {
            String md = export(List.of(AIChatMessage.user("one\ntwo")));
            assertThat(md).contains("## You\n\none\ntwo\n\n");
        }
    }

    @Nested
    @DisplayName("ASSISTANT messages")
    class Assistant {

        @Test
        @DisplayName("Includes model name in the header when known")
        void withModel() throws IOException {
            String md = export(List.of(
                    AIChatMessage.assistant("hi", 0L, "qwen2.5:7b")));
            assertThat(md).contains("## AI (qwen2.5:7b)\n\n");
            assertThat(md).contains("hi");
        }

        @Test
        @DisplayName("Falls back to bare '## AI' when model is null")
        void nullModel() throws IOException {
            String md = export(List.of(
                    AIChatMessage.assistant("hi", 0L, null)));
            assertThat(md).contains("## AI\n\n");
            assertThat(md).doesNotContain("## AI (");
        }

        @Test
        @DisplayName("Falls back to bare '## AI' when model is blank")
        void blankModel() throws IOException {
            String md = export(List.of(
                    AIChatMessage.assistant("hi", 0L, "   ")));
            assertThat(md).contains("## AI\n\n");
            assertThat(md).doesNotContain("## AI (");
        }

        @Test
        @DisplayName("Duration line appears when durationMillis > 0")
        void withDuration() throws IOException {
            String md = export(List.of(
                    AIChatMessage.assistant("hi", 1500L, "qwen2.5:7b")));
            assertThat(md).contains("_1.5 s_");
        }

        @Test
        @DisplayName("No duration line when durationMillis == 0")
        void zeroDuration() throws IOException {
            String md = export(List.of(
                    AIChatMessage.assistant("hi", 0L, "qwen2.5:7b")));
            assertThat(md).doesNotContain("_0 ms_");
            assertThat(md).doesNotContain("_0.0 s_");
        }
    }

    @Nested
    @DisplayName("SYSTEM messages")
    class System {

        @Test
        @DisplayName("Single-line system is a blockquote")
        void singleLine() throws IOException {
            String md = export(List.of(AIChatMessage.system("be concise")));
            assertThat(md).contains("> be concise");
        }

        @Test
        @DisplayName("Multi-line system is quoted line by line")
        void multiLine() throws IOException {
            String md = export(List.of(AIChatMessage.system("line one\nline two")));
            assertThat(md).contains("> line one\n> line two");
        }
    }

    @Nested
    @DisplayName("Conversation order")
    class Order {

        @Test
        @DisplayName("Messages appear in the order given")
        void preserved() throws IOException {
            String md = export(List.of(
                    AIChatMessage.system("prompt"),
                    AIChatMessage.user("q1"),
                    AIChatMessage.assistant("a1", 0L, "m"),
                    AIChatMessage.user("q2"),
                    AIChatMessage.assistant("a2", 0L, "m")));

            int sys = md.indexOf("> prompt");
            int q1 = md.indexOf("## You\n\nq1");
            int a1 = md.indexOf("## AI (m)\n\na1");
            int q2 = md.indexOf("## You\n\nq2");
            int a2 = md.indexOf("## AI (m)\n\na2");

            assertThat(sys).isLessThan(q1);
            assertThat(q1).isLessThan(a1);
            assertThat(a1).isLessThan(q2);
            assertThat(q2).isLessThan(a2);
        }
    }

    @Nested
    @DisplayName("quoteSystem")
    class QuoteSystem {

        @Test
        @DisplayName("Single line prefixed with '> '")
        void single() {
            assertThat(ChatExporter.quoteSystem("hello"))
                    .isEqualTo("> hello");
        }

        @Test
        @DisplayName("Each line prefixed, no trailing newline added")
        void multi() {
            assertThat(ChatExporter.quoteSystem("a\nb\nc"))
                    .isEqualTo("> a\n> b\n> c");
        }

        @Test
        @DisplayName("Trailing whitespace at the end is stripped")
        void trailingStrip() {
            assertThat(ChatExporter.quoteSystem("a\n"))
                    .isEqualTo("> a");
        }

        @Test
        @DisplayName("Empty input produces a bare '>' prefix")
        void empty() {
            assertThat(ChatExporter.quoteSystem(""))
                    .isEqualTo("> ");
        }
    }

    @Nested
    @DisplayName("File writing")
    class FileWriting {

        @Test
        @DisplayName("Output is UTF-8 and survives round-trip")
        void utf8() throws IOException {
            String original = "Привет, мир";
            String md = export(List.of(AIChatMessage.user(original)));
            assertThat(md).contains(original);
        }
    }

    @Nested
    @DisplayName("JSON export")
    class Json {

        @Test
        @DisplayName("Has exported timestamp and messages array")
        void header() throws IOException {
            JsonNode root = exportJson(List.of());
            assertThat(root.get("exported").asText()).isNotBlank();
            assertThat(root.get("messages").isArray()).isTrue();
        }

        @Test
        @DisplayName("Empty history produces empty messages array")
        void empty() throws IOException {
            JsonNode root = exportJson(List.of());
            assertThat(root.get("messages")).isEmpty();
        }

        @Test
        @DisplayName("One object per message, in order")
        void order() throws IOException {
            AIChatMessage user = AIChatMessage.user("q");
            AIChatMessage assistant = AIChatMessage.assistant("a", 1234L, "qwen2.5:7b");

            JsonNode messages = exportJson(List.of(user, assistant)).get("messages");

            assertThat(messages).hasSize(2);
            assertThat(messages.get(0).get("role").asText()).isEqualTo("USER");
            assertThat(messages.get(1).get("role").asText()).isEqualTo("ASSISTANT");
        }

        @Test
        @DisplayName("USER message keeps id, text, null model")
        void user() throws IOException {
            AIChatMessage user = AIChatMessage.user("hello");
            JsonNode node = exportJson(List.of(user)).get("messages").get(0);

            assertThat(node.get("id").asText()).isEqualTo(user.id());
            assertThat(node.get("text").asText()).isEqualTo("hello");
            assertThat(node.get("model").isNull()).isTrue();
            assertThat(node.get("durationMillis").asLong()).isZero();
        }

        @Test
        @DisplayName("ASSISTANT message keeps model and duration")
        void assistant() throws IOException {
            AIChatMessage a = AIChatMessage.assistant("hi", 1234L, "qwen2.5:7b");
            JsonNode node = exportJson(List.of(a)).get("messages").get(0);

            assertThat(node.get("role").asText()).isEqualTo("ASSISTANT");
            assertThat(node.get("model").asText()).isEqualTo("qwen2.5:7b");
            assertThat(node.get("durationMillis").asLong()).isEqualTo(1234L);
        }

        @Test
        @DisplayName("SYSTEM message has null model")
        void system() throws IOException {
            JsonNode node = exportJson(List.of(AIChatMessage.system("prompt")))
                    .get("messages").get(0);

            assertThat(node.get("role").asText()).isEqualTo("SYSTEM");
            assertThat(node.get("model").isNull()).isTrue();
        }

        @Test
        @DisplayName("UTF-8 round-trip")
        void utf8() throws IOException {
            JsonNode node = exportJson(List.of(AIChatMessage.user("Привет, мир")))
                    .get("messages").get(0);

            assertThat(node.get("text").asText()).isEqualTo("Привет, мир");
        }
    }

    @Nested
    @DisplayName("HTML export")
    class Html {

        @Test
        @DisplayName("Starts with doctype and html tag")
        void doctype() throws IOException {
            String html = exportHtml(List.of());
            assertThat(html).startsWith("<!DOCTYPE html>");
            assertThat(html).contains("<html lang=\"en\">");
            assertThat(html).contains("</html>");
        }

        @Test
        @DisplayName("Has charset meta and title")
        void head() throws IOException {
            String html = exportHtml(List.of());
            assertThat(html).contains("<meta charset=\"UTF-8\">");
            assertThat(html).contains("<title>AIChat conversation</title>");
        }

        @Test
        @DisplayName("Has exported timestamp")
        void timestamp() throws IOException {
            String html = exportHtml(List.of());
            assertThat(html).contains("Exported: ");
        }

        @Test
        @DisplayName("USER message uses .user class and 'You' role")
        void user() throws IOException {
            String html = exportHtml(List.of(AIChatMessage.user("hello")));
            assertThat(html).contains("class=\"msg user\"");
            assertThat(html).contains("<div class=\"role\">You</div>");
            assertThat(html).contains("<div class=\"body\">hello</div>");
        }

        @Test
        @DisplayName("ASSISTANT message includes model name")
        void assistantWithModel() throws IOException {
            String html = exportHtml(List.of(
                    AIChatMessage.assistant("hi", 0L, "qwen2.5:7b")));
            assertThat(html).contains("class=\"msg assistant\"");
            assertThat(html).contains("AI (qwen2.5:7b)");
        }

        @Test
        @DisplayName("ASSISTANT message falls back to bare 'AI' when model is null")
        void assistantNullModel() throws IOException {
            String html = exportHtml(List.of(AIChatMessage.assistant("hi", 0L, null)));
            assertThat(html).contains("<div class=\"role\">AI</div>");
            assertThat(html).doesNotContain("AI (");
        }

        @Test
        @DisplayName("SYSTEM message uses .system class")
        void system() throws IOException {
            String html = exportHtml(List.of(AIChatMessage.system("prompt")));
            assertThat(html).contains("class=\"msg system\"");
            assertThat(html).contains("prompt");
        }

        @Test
        @DisplayName("HTML special characters are escaped")
        void escaping() throws IOException {
            String html = exportHtml(List.of(AIChatMessage.user("<script>alert(1)</script>")));
            assertThat(html).doesNotContain("<script>");
            assertThat(html).contains("&lt;script&gt;");
        }

        @Test
        @DisplayName("Ampersand is escaped")
        void ampersand() throws IOException {
            String html = exportHtml(List.of(AIChatMessage.user("a & b")));
            assertThat(html).contains("a &amp; b");
        }

        @Test
        @DisplayName("UTF-8 round-trip")
        void utf8() throws IOException {
            String html = exportHtml(List.of(AIChatMessage.user("Привет, мир")));
            assertThat(html).contains("Привет, мир");
        }
    }

    @Nested
    @DisplayName("PDF export")
    class Pdf {

        @Test
        @DisplayName("Creates a file with %PDF- header")
        void header() throws IOException {
            Path file = exportPdf(List.of(AIChatMessage.user("hi")));

            assertThat(file).exists();
            byte[] bytes = Files.readAllBytes(file);
            assertThat(bytes.length).isGreaterThan(100);
            assertThat(new String(bytes, 0, 5, StandardCharsets.US_ASCII))
                    .isEqualTo("%PDF-");
        }

        @Test
        @DisplayName("Empty history still produces a valid PDF")
        void empty() throws IOException {
            Path file = exportPdf(List.of());

            assertThat(file).exists();
            byte[] bytes = Files.readAllBytes(file);
            assertThat(bytes.length).isGreaterThan(100);
            assertThat(new String(bytes, 0, 5, StandardCharsets.US_ASCII))
                    .isEqualTo("%PDF-");
        }

        @Test
        @DisplayName("Assorted roles and UTF-8 text do not break export")
        void assortedRoles() throws IOException {
            Path file = exportPdf(List.of(
                    AIChatMessage.system("be concise"),
                    AIChatMessage.user("Привет"),
                    AIChatMessage.assistant("hi", 0L, "qwen2.5:7b")));

            assertThat(file).exists();
            assertThat(Files.size(file)).isGreaterThan(100);
        }

        @Test
        @DisplayName("ASSISTANT message with null model uses bare 'AI' header")
        void assistantNullModel() throws IOException {
            Path file = exportPdf(List.of(
                    AIChatMessage.assistant("hi", 0L, null)));
            assertThat(file).exists();
            assertThat(Files.size(file)).isGreaterThan(100);
        }
    }

    @Test
    @DisplayName("PDF is readable and contains user and assistant messages")
    void containsConversationText() throws IOException {
        Path file = exportPdf(List.of(
                AIChatMessage.user("Hello from user"),
                AIChatMessage.assistant("Hello from assistant", 0L, "qwen2.5:7b")
        ));

        try (PdfReader reader = new PdfReader(Files.readAllBytes(file))) {
            String text = new PdfTextExtractor(reader).getTextFromPage(1);

            assertThat(text)
                    .contains("You")
                    .contains("Hello from user")
                    .contains("AI (qwen2.5:7b)")
                    .contains("Hello from assistant");
        }
    }

    private JsonNode exportJson(List<AIChatMessage> history) throws IOException {
        Path file = tmp.resolve("chat.json");
        ChatExporter.exportJson(history, file);
        return JSON.readTree(file.toFile());
    }

    private String exportHtml(List<AIChatMessage> history) throws IOException {
        Path file = tmp.resolve("chat.html");
        ChatExporter.exportHtml(history, file);
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private Path exportPdf(List<AIChatMessage> history) throws IOException {
        Path file = tmp.resolve("chat.pdf");
        ChatExporter.exportPdf(history, file);
        return file;
    }
}
