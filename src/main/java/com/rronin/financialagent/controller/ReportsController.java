package com.rronin.financialagent.controller;

import com.rronin.financialagent.config.AgentProperties;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/reports")
public class ReportsController {
    private final Path reportsRoot;

    public ReportsController(AgentProperties properties) {
        this.reportsRoot = properties.filesystem().root().toAbsolutePath().normalize().resolve("reports").normalize();
    }

    @GetMapping
    public List<Map<String, Object>> list() throws IOException {
        if (!Files.exists(reportsRoot)) return List.of();
        try (Stream<Path> stream = Files.walk(reportsRoot, 4)) {
            return stream.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".md"))
                    .map(this::summary)
                    .sorted(Comparator.comparing(item -> String.valueOf(item.get("modifiedAt")), Comparator.reverseOrder()))
                    .toList();
        }
    }

    @GetMapping(value = "/content", produces = MediaType.TEXT_PLAIN_VALUE)
    public String content(@RequestParam String path) throws IOException {
        Path resolved = resolveReport(path);
        return Files.readString(resolved, StandardCharsets.UTF_8);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@RequestParam String path) throws IOException {
        Path resolved = resolveReport(path);
        Files.deleteIfExists(resolved);
        deleteEmptyParentDirectories(resolved.getParent());
    }

    private Map<String, Object> summary(Path path) {
        try {
            Path relative = reportsRoot.relativize(path);
            String content = Files.readString(path, StandardCharsets.UTF_8);
            String title = content.lines().filter(line -> line.startsWith("# ")).findFirst()
                    .map(line -> line.substring(2).trim()).orElse(path.getFileName().toString());
            return Map.of(
                    "path", relative.toString(),
                    "title", title,
                    "ticker", relative.getNameCount() > 1 ? relative.getName(0).toString() : "Reports",
                    "modifiedAt", Instant.ofEpochMilli(Files.getLastModifiedTime(path).toMillis()).toString(),
                    "bytes", Files.size(path)
            );
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    private Path resolveReport(String requestedPath) {
        Path resolved = reportsRoot.resolve(requestedPath).toAbsolutePath().normalize();
        if (!resolved.startsWith(reportsRoot) || !resolved.getFileName().toString().endsWith(".md")) {
            throw new IllegalArgumentException("Report path is outside the reports directory or is not a Markdown file");
        }
        return resolved;
    }

    private void deleteEmptyParentDirectories(Path start) throws IOException {
        Path current = start;
        while (current != null && current.startsWith(reportsRoot) && !current.equals(reportsRoot)) {
            try (Stream<Path> children = Files.list(current)) {
                if (children.findAny().isPresent()) return;
            }
            Files.deleteIfExists(current);
            current = current.getParent();
        }
    }
}
