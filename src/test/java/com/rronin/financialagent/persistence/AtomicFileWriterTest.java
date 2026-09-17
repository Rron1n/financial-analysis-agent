package com.rronin.financialagent.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class AtomicFileWriterTest {
    @TempDir Path directory;
    private final AtomicFileWriter failingMove = new AtomicFileWriter() {
        @Override protected void move(Path from, Path to) throws IOException { throw new IOException("test rename failure"); }
    };
    @Test void memoryFallbackFlushesNewContentsAndCleansTemporaryFile() throws Exception {
        Path file = directory.resolve("session-memory.md");
        Files.writeString(file, "old");
        failingMove.write(file, "new", true);
        assertThat(Files.readString(file)).isEqualTo("new");
        try (var files = Files.list(directory)) { assertThat(files.toList()).containsExactly(file); }
    }
    @Test void metadataDoesNotUseUnsafeFallback() throws Exception {
        Path file = directory.resolve("metadata.json");
        Files.writeString(file, "old");
        assertThatThrownBy(() -> failingMove.write(file, "new", false)).isInstanceOf(IOException.class);
        assertThat(Files.readString(file)).isEqualTo("old");
    }
}
