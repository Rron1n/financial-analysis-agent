package com.rronin.financialagent.controller;

import com.rronin.financialagent.tools.file.FileGuard;
import org.springframework.http.HttpStatus;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/files")
public class FileUploadController {
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "txt", "md", "markdown", "csv", "tsv", "json", "jsonl", "xml", "html", "htm", "log", "yml", "yaml", "pdf"
    );

    private final FileGuard guard;

    public FileUploadController(FileGuard guard) {
        this.guard = guard;
    }





    @GetMapping("/raw")
    public ResponseEntity<Resource> raw(@RequestParam String path) {
        Path resolved = guard.resolve(path);
        if (!Files.isRegularFile(resolved)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found");
        }
        String filename = resolved.getFileName().toString().toLowerCase(Locale.ROOT);
        MediaType mediaType = filename.endsWith(".pdf") ? MediaType.APPLICATION_PDF : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok()
                .contentType(mediaType)
                .body(new FileSystemResource(resolved));
    }

    @GetMapping("/download")
    public ResponseEntity<Resource> download(@RequestParam String path) throws Exception {
        Path resolved = guard.resolve(path);
        if (!Files.isRegularFile(resolved)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found");
        }
        String filename = resolved.getFileName().toString();
        String detected = Files.probeContentType(resolved);
        MediaType mediaType = detected == null
                ? MediaType.APPLICATION_OCTET_STREAM
                : MediaType.parseMediaType(detected);
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString())
                .contentLength(Files.size(resolved))
                .body(new FileSystemResource(resolved));
    }

    @GetMapping(value = "/content", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> content(@RequestParam String path) throws Exception {
        Path resolved = guard.resolve(path);
        if (!Files.isRegularFile(resolved)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found");
        }
        String filename = resolved.getFileName().toString();
        if (filename.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            return pdfPreview(resolved, filename);
        }
        String content = Files.readString(resolved, StandardCharsets.UTF_8);
        int maxChars = 40_000;
        boolean truncated = content.length() > maxChars;
        return Map.of(
                "name", filename,
                "path", path,
                "fileType", "text",
                "truncated", truncated,
                "content", truncated ? content.substring(0, maxChars) : content
        );
    }

    private static Map<String, Object> pdfPreview(Path path, String filename) throws Exception {
        int maxPages = 10;
        int maxChars = 40_000;
        try (PDDocument document = PDDocument.load(path.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(Math.min(maxPages, document.getNumberOfPages()));
            String content = stripper.getText(document).replaceAll("\\r\\n?", "\n").trim();
            boolean truncated = content.length() > maxChars || document.getNumberOfPages() > maxPages;
            return Map.of(
                    "name", filename,
                    "path", path.toString(),
                    "fileType", "pdf",
                    "totalPages", document.getNumberOfPages(),
                    "previewPages", Math.min(maxPages, document.getNumberOfPages()),
                    "truncated", truncated,
                    "content", content.length() > maxChars ? content.substring(0, maxChars) : content
            );
        }
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<Map<String, Object>> upload(@RequestPart("file") FilePart file) {
        String original = sanitize(file.filename());
        String extension = extension(original);
        MediaType contentType = file.headers().getContentType();
        if (!isAllowed(extension, contentType)) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only text-like files and PDFs are supported: " + ALLOWED_EXTENSIONS));
        }
        String relativePath = "uploads/" + Instant.now().toEpochMilli() + "-" + original;
        Path target = guard.resolve(relativePath);
        try {
            Files.createDirectories(target.getParent());
        } catch (Exception error) {
            return Mono.error(error);
        }
        return file.transferTo(target).then(Mono.fromCallable(() -> Map.of(
                "name", original,
                "path", relativePath,
                "size", Files.size(target),
                "contentType", contentType == null ? "text/plain" : contentType.toString()
        )));
    }

    private static boolean isAllowed(String extension, MediaType contentType) {
        if (ALLOWED_EXTENSIONS.contains(extension)) return true;
        if (contentType == null) return false;
        String type = contentType.toString().toLowerCase(Locale.ROOT);
        return type.startsWith("text/")
                || type.contains("pdf")
                || type.contains("json")
                || type.contains("xml")
                || type.contains("yaml")
                || type.contains("csv")
                || type.contains("markdown");
    }

    private static String sanitize(String value) {
        String name = value == null || value.isBlank() ? "upload.txt" : Path.of(value).getFileName().toString();
        name = name.replaceAll("[^A-Za-z0-9._-]", "-");
        return name.isBlank() ? "upload.txt" : name;
    }

    private static String extension(String filename) {
        int idx = filename.lastIndexOf('.');
        return idx < 0 ? "" : filename.substring(idx + 1).toLowerCase(Locale.ROOT);
    }
}
