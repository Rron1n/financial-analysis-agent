package com.rronin.financialagent.tools.file;

import com.rronin.financialagent.config.AgentProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.util.Set;

@Component
public class FileGuard {
    private final AgentProperties properties;

    public FileGuard(AgentProperties properties) {
        this.properties = properties;
    }

    public Path resolve(String requestedPath) {
        if (requestedPath == null || requestedPath.isBlank()) throw new IllegalArgumentException("File path is required");
        Path root = properties.filesystem().root().toAbsolutePath().normalize();
        Path resolved = root.resolve(requestedPath).toAbsolutePath().normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Path is outside the configured filesystem root");
        }
        for (Path segment : root.relativize(resolved)) {
            String name = segment.toString().toLowerCase(java.util.Locale.ROOT);
            if (Set.of(".git", ".secrets", ".env", ".ssh").contains(name)
                    || name.startsWith(".env.") || name.equals("application-secrets.yml") || name.equals("application-secrets.yaml"))
                throw new IllegalArgumentException("Credential and security configuration paths are not available to file tools");
        }
        // Reject symlink components, including existing parents of a not-yet-created output file.
        Path current = root;
        for (Path segment : root.relativize(resolved)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) throw new IllegalArgumentException("Symbolic-link file paths are not allowed");
        }
        try {
            if (Files.exists(root) && Files.exists(resolved) && !resolved.toRealPath().startsWith(root.toRealPath()))
                throw new IllegalArgumentException("Resolved path escapes filesystem root");
        } catch (IOException error) { throw new IllegalArgumentException("Unable to validate file path", error); }
        return resolved;
    }

    public boolean isInternalDraft(String requestedPath) {
        return resolve(requestedPath).startsWith(properties.filesystem().root().toAbsolutePath().normalize().resolve(".financial-agent/scratch"));
    }

    public int maxReadChars() {
        return properties.filesystem().maxReadChars();
    }

    public Path resolveRead(String requestedPath, java.util.List<Path> allowedRoots) {
        try { return resolve(requestedPath); }
        catch (IllegalArgumentException outsideWorkspace) {
            Path candidate = Path.of(requestedPath).toAbsolutePath().normalize();
            for (Path allowed : allowedRoots) {
                Path root = allowed.toAbsolutePath().normalize();
                if (!candidate.startsWith(root) || root.equals(properties.filesystem().root().toAbsolutePath().normalize())) continue;
                Path current = root;
                if (Files.isSymbolicLink(current)) continue;
                boolean symlink = false;
                for (Path part : root.relativize(candidate)) {
                    current = current.resolve(part);
                    if (Files.isSymbolicLink(current)) { symlink = true; break; }
                }
                if (!symlink && candidate.getFileName().toString().matches("[A-Za-z0-9_-]+\\.json") && root.getFileName().toString().equals("tool-results")) return candidate;
            }
            throw outsideWorkspace;
        }
    }
}
