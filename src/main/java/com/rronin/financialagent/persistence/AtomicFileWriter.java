package com.rronin.financialagent.persistence;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;

/** Direct overwrite fallback is opt-in and reserved for extraction-owned memory files. */
public class AtomicFileWriter {
    public void write(Path target, String content, boolean allowDirectFallback) throws IOException {
        target = target.toAbsolutePath().normalize();
        Files.createDirectories(target.getParent());
        if (Files.isSymbolicLink(target)) throw new IOException("Refusing symbolic-link write target");
        Path temporary = null;
        try {
            temporary = Files.createTempFile(target.getParent(), ".agent-", ".tmp");
            try {
                Files.setPosixFilePermissions(temporary, Files.exists(target)
                        ? Files.getPosixFilePermissions(target) : PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) { }
            flush(temporary, content);
            move(temporary, target);
        } catch (IOException atomicError) {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException cleanupError) { atomicError.addSuppressed(cleanupError); }
            }
            if (!allowDirectFallback) throw atomicError;
            if (Files.isSymbolicLink(target)) throw new IOException("Refusing symbolic-link fallback", atomicError);
            try { flush(target, content); }
            catch (IOException fallbackError) { fallbackError.addSuppressed(atomicError); throw fallbackError; }
        }
    }
    protected void move(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    private void flush(Path target, String content) throws IOException {
        try (FileChannel channel = FileChannel.open(target, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer bytes = StandardCharsets.UTF_8.encode(content);
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        }
    }
}
