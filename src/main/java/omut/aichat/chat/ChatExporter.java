package omut.aichat.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lowagie.text.*;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfWriter;
import omut.aichat.utils.TimeFormat;

import java.awt.Color;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class ChatExporter {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final BaseFont BASE_FONT = loadBaseFont();

    private static BaseFont loadBaseFont() {
        try (var in = ChatExporter.class.getResourceAsStream("/font/DejaVuSans.ttf")) {
            if (in == null) {
                throw new IllegalStateException(
                        "Font not found on classpath: /font/DejaVuSans.ttf");
            }
            return BaseFont.createFont(
                    "DejaVuSans.ttf", BaseFont.IDENTITY_H, BaseFont.EMBEDDED,
                    true, in.readAllBytes(), null);
        } catch (DocumentException | IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** Exports the conversation to a Markdown file. */
    public static void exportMarkdown(List<AIChatMessage> history, Path file) throws IOException {
        StringBuilder sb = new StringBuilder();

        sb.append("# AIChat conversation\n\n");
        sb.append("Exported: ").append(LocalDateTime.now().format(TIMESTAMP)).append("\n\n");
        sb.append("---\n\n");

        for (AIChatMessage msg : history) {
            switch (msg.role()) {
                case USER -> {
                    sb.append("## You\n\n");
                    sb.append(msg.text()).append("\n\n");
                }
                case ASSISTANT -> {
                    if (msg.model() != null && !msg.model().isBlank()) {
                        sb.append("## AI (").append(msg.model()).append(")\n\n");
                    } else {
                        sb.append("## AI\n\n");
                    }
                    if (msg.durationMillis() > 0) {
                        sb.append("_").append(TimeFormat.shortDuration(msg.durationMillis())).append("_\n\n");
                    }
                    sb.append(msg.text()).append("\n\n");
                }
                case SYSTEM -> sb.append(quoteSystem(msg.text())).append("\n\n");
            }
        }

        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    /**
     * Formats a system message as a Markdown blockquote.
     * Multi-line messages are quoted line by line. Trailing whitespace
     * in the input is stripped before quoting, so a trailing newline
     * does not leave a dangling {@code >} with no content.
     */
    public static String quoteSystem(String text) {
        return "> " + text.stripTrailing().replace("\n", "\n> ");
    }

    /** Exports the conversation to a JSON file. */
    public static void exportJson(List<AIChatMessage> history, Path file) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        root.put("exported", LocalDateTime.now().format(TIMESTAMP));

        ArrayNode messages = root.putArray("messages");
        for (AIChatMessage msg : history) {
            ObjectNode node = messages.addObject();
            node.put("id", msg.id());
            node.put("role", msg.role().name());
            node.put("text", msg.text());
            node.put("timestamp", msg.timestamp());
            node.put("durationMillis", msg.durationMillis());
            node.put("model", msg.model());
        }

        mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), root);
    }

    /** Exports the conversation to a self-contained HTML file. */
    public static void exportHtml(List<AIChatMessage> history, Path file) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n");
        sb.append("<meta charset=\"UTF-8\">\n");
        sb.append("<title>AIChat conversation</title>\n");
        sb.append("<style>\n");
        sb.append("body{font-family:-apple-system,Segoe UI,Roboto,sans-serif;");
        sb.append("max-width:800px;margin:2em auto;padding:0 1em;line-height:1.5;}\n");
        sb.append(".meta{color:#666;font-size:.9em;}\n");
        sb.append(".msg{margin:1.5em 0;}\n");
        sb.append(".role{font-weight:bold;margin-bottom:.4em;}\n");
        sb.append(".user .role{color:#0366d6;}\n");
        sb.append(".assistant .role{color:#6f42c1;}\n");
        sb.append(".system{color:#666;font-style:italic;border-left:3px solid #ddd;padding-left:1em;}\n");
        sb.append(".body{white-space:pre-wrap;word-wrap:break-word;}\n");
        sb.append("</style>\n</head>\n<body>\n");
        sb.append("<h1>AIChat conversation</h1>\n");
        sb.append("<p class=\"meta\">Exported: ")
                .append(LocalDateTime.now().format(TIMESTAMP)).append("</p>\n<hr>\n");

        for (AIChatMessage msg : history) {
            switch (msg.role()) {
                case USER -> appendHtmlMsg(sb, "user", "You", msg.text());
                case ASSISTANT -> appendHtmlMsg(sb, "assistant",
                        msg.model() != null && !msg.model().isBlank()
                                ? "AI (" + msg.model() + ")" : "AI",
                        msg.text());
                case SYSTEM -> sb.append("<div class=\"msg system\"><div class=\"body\">")
                        .append(escapeHtml(msg.text()))
                        .append("</div></div>\n");
            }
        }

        sb.append("</body>\n</html>\n");
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    /** Exports the conversation to a PDF file. */
    public static void exportPdf(List<AIChatMessage> history, Path file) throws IOException {
        try (PdfDocument pdf = new PdfDocument(file)) {
            Font titleFont = new Font(BASE_FONT, 16, Font.BOLD);
            Font metaFont  = new Font(BASE_FONT, 9,  Font.ITALIC, new Color(120, 120, 120));
            Font roleFont  = new Font(BASE_FONT, 12, Font.BOLD);
            Font bodyFont  = new Font(BASE_FONT, 11, Font.NORMAL);
            Font sysFont   = new Font(BASE_FONT, 10, Font.ITALIC, new Color(100, 100, 100));

            pdf.add(new Paragraph("AIChat conversation", titleFont));
            pdf.add(new Paragraph("Exported: " + LocalDateTime.now().format(TIMESTAMP), metaFont));
            pdf.add(new Paragraph(" "));

            for (AIChatMessage msg : history) {
                boolean system = msg.role() == AIChatMessage.Role.SYSTEM;
                Font hdr  = system ? sysFont : roleFont;
                Font body = system ? sysFont : bodyFont;

                pdf.add(new Paragraph(roleHeader(msg), hdr));
                for (String line : msg.text().split("\n", -1)) {
                    Paragraph p = new Paragraph(line, body);
                    p.setSpacingAfter(0f);
                    pdf.add(p);
                }
                pdf.add(new Paragraph(" "));
            }
        } catch (DocumentException e) {
            throw new IOException("Failed to write PDF", e);
        }
    }

    private static final class PdfDocument implements AutoCloseable {
        private final Document doc = new Document();

        PdfDocument(Path file) throws IOException {
            OutputStream out = Files.newOutputStream(file);
            try {
                PdfWriter.getInstance(doc, out);
                doc.open();
            } catch (DocumentException e) {
                try { out.close(); } catch (IOException ignored) { }
                throw new IOException("Failed to write PDF", e);
            }
        }

        void add(Element e) throws DocumentException { doc.add(e); }

        @Override public void close() { doc.close(); }
    }

    private static String roleHeader(AIChatMessage msg) {
        return switch (msg.role()) {
            case USER -> "You";
            case ASSISTANT -> msg.model() != null && !msg.model().isBlank()
                    ? "AI (" + msg.model() + ")" : "AI";
            case SYSTEM -> "System";
        };
    }

    private static void appendHtmlMsg(StringBuilder sb, String cls, String role, String text) {
        sb.append("<div class=\"msg ").append(cls).append("\">\n");
        sb.append("<div class=\"role\">").append(escapeHtml(role)).append("</div>\n");
        sb.append("<div class=\"body\">").append(escapeHtml(text)).append("</div>\n");
        sb.append("</div>\n");
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}