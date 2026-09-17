package com.rronin.financialagent.tools.file;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rronin.financialagent.model.AgentModels.ToolResult;
import com.rronin.financialagent.tools.AgentTool;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@Component
public class ReadFileTool implements AgentTool {
    private static final int DEFAULT_PDF_MAX_PAGES = 20;
    private static final int HARD_PDF_MAX_PAGES = 60;

    private final FileGuard guard;
    private final ObjectMapper mapper;

    public ReadFileTool(FileGuard guard, ObjectMapper mapper) {
        this.guard = guard;
        this.mapper = mapper;
    }

    public String name() { return "read_file"; }

    public String description() {
        return "Read a file under the configured workspace root. Supports UTF-8 text/Markdown files and PDF text extraction for uploaded filings, reports or presentations. Read-only; PDF reading has page and character limits and does not perform OCR.";
    }

    public Mode mode() { return Mode.READ_ONLY; }
    public java.util.List<String> aliases() { return java.util.List.of("Read"); }
    public boolean isReadOnly(JsonNode input) { return true; }
    public boolean isConcurrencySafe(JsonNode input) { return true; }
    public InterruptBehavior interruptBehavior() { return InterruptBehavior.CANCEL; }
    public java.util.Optional<com.rronin.financialagent.agent.ApprovalBehaviour> evaluateApproval(JsonNode input, ApprovalContext context) {
        guard.resolveRead(input.path("path").asText(), context.allowedRoots());
        return java.util.Optional.of(com.rronin.financialagent.agent.ApprovalBehaviour.ALLOW);
    }

    public JsonNode inputSchema() {
        return mapper.valueToTree(Map.of(
                "type", "object",
                "required", new String[]{"path"},
                "properties", Map.of(
                        "path", Map.of("type", "string", "description", "Relative path to a local/uploaded file under the workspace root."),
                        "offset", Map.of("type", "integer", "minimum", 0, "description", "For text files: zero-based UTF-16 character offset. Use nextOffset from the preceding result to continue reading a large file."),
                        "start_page", Map.of("type", "integer", "minimum", 1, "description", "For PDF files only: 1-based page number to start extraction. Default 1."),
                        "max_pages", Map.of("type", "integer", "minimum", 1, "maximum", HARD_PDF_MAX_PAGES, "description", "For PDF files only: maximum pages to extract. Default 20, hard capped at 60."),
                        "max_chars", Map.of("type", "integer", "minimum", 1, "maximum", 100000, "description", "Maximum returned characters. Capped by filesystem.max-read-chars.")
                ),
                "additionalProperties", false
        ));
    }

    public Mono<ToolResult> execute(JsonNode args) {
        return executeRead(args, java.util.List.of());
    }
    @Override public Mono<ToolResult> execute(JsonNode args, ApprovalContext context) {
        return executeRead(args, context.allowedRoots());
    }
    private Mono<ToolResult> executeRead(JsonNode args, java.util.List<Path> roots) {
        return Mono.fromCallable(() -> {
            Path path = guard.resolveRead(args.path("path").asText(), roots);
            if (!Files.isRegularFile(path)) throw new IllegalArgumentException("File not found");
            if (isPdf(path)) return readPdf(path, args);
            int max = Math.max(1, Math.min(args.path("max_chars").asInt(guard.maxReadChars()), guard.maxReadChars()));
            long offset = args.path("offset").asLong(0);
            if (offset < 0) throw new IllegalArgumentException("Read offset must not be negative");
            // Persisted tool envelopes contain JSON strings. Read their source text directly,
            // so offsets address the document, not successive layers of JSON escaping.
            if (path.getParent().getFileName().toString().equals("tool-results") && path.toString().endsWith(".json")) {
                JsonNode envelope = mapper.readTree(Files.readString(path));
                JsonNode source = envelope.path("content");
                if (source.isTextual()) {
                    try { source = mapper.readTree(source.asText()); } catch (Exception ignored) { }
                }
                JsonNode document = source.path("data").path("content");
                String plain = document.isTextual() ? document.asText() : source.isTextual() ? source.asText() : mapper.writerWithDefaultPrettyPrinter().writeValueAsString(source);
                int from = (int)Math.min(offset, plain.length()), to = Math.min(plain.length(), from + Math.min(max, 8000));
                return ToolResult.ok(name(), Map.of("path", path.toString(), "fileType", "text", "offset", from,
                        "nextOffset", to, "truncated", to < plain.length(), "content", plain.substring(from, to)));
            }
            String content;
            boolean truncated;
            try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                long remaining = offset;
                while (remaining > 0) {
                    long skipped = reader.skip(remaining);
                    if (skipped == 0) { if (reader.read() == -1) break; skipped = 1; }
                    remaining -= skipped;
                }
                char[] buffer = new char[max];
                int length = 0, count;
                while (length < max && (count = reader.read(buffer, length, max - length)) != -1) length += count;
                content = new String(buffer, 0, length);
                truncated = reader.read() != -1;
            }
            return ToolResult.ok(name(), Map.of(
                    "path", path.toString(),
                    "fileType", "text",
                    "truncated", truncated,
                    "offset", offset,
                    "nextOffset", offset + content.length(),
                    "content", content
            ));
        });
    }

    private boolean isPdf(Path path) {
        return path.getFileName().toString().toLowerCase().endsWith(".pdf");
    }

    private ToolResult readPdf(Path path, JsonNode args) throws Exception {
        int startPage = Math.max(1, args.path("start_page").asInt(1));
        int maxPages = Math.max(1, Math.min(args.path("max_pages").asInt(DEFAULT_PDF_MAX_PAGES), HARD_PDF_MAX_PAGES));
        int maxChars = Math.min(args.path("max_chars").asInt(guard.maxReadChars()), guard.maxReadChars());
        try (PDDocument document = PDDocument.load(path.toFile())) {
            int totalPages = document.getNumberOfPages();
            int endPage = Math.min(totalPages, startPage + maxPages - 1);
            if (startPage > totalPages) {
                return ToolResult.ok(name(), Map.of(
                        "path", path.toString(),
                        "fileType", "pdf",
                        "totalPages", totalPages,
                        "startPage", startPage,
                        "endPage", totalPages,
                        "extractedPages", 0,
                        "truncated", false,
                        "content", ""
                ));
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(startPage);
            stripper.setEndPage(endPage);
            String text = stripper.getText(document).replaceAll("\\r\\n?", "\n").trim();
            boolean truncated = text.length() > maxChars;
            return ToolResult.ok(name(), Map.of(
                    "path", path.toString(),
                    "fileType", "pdf",
                    "totalPages", totalPages,
                    "startPage", startPage,
                    "endPage", endPage,
                    "extractedPages", Math.max(0, endPage - startPage + 1),
                    "truncated", truncated,
                    "content", truncated ? text.substring(0, maxChars) : text
            ));
        }
    }
}
