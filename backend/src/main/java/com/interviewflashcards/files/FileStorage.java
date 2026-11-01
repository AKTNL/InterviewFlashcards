package com.interviewflashcards.files;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.core.io.Resource;

public interface FileStorage {
    void checkSpace(long incoming) throws IOException;
    Staged stage(InputStream input) throws IOException;
    void publish(Staged staged, UUID key) throws IOException;
    Resource read(UUID key);
    void delete(UUID key);
    /** Reclamation must retain its retry marker until absence is confirmed. */
    boolean reclaim(UUID key);
    record Staged(Path path, String sha256, long size) implements AutoCloseable {
        @Override public void close() throws IOException { java.nio.file.Files.deleteIfExists(path); }
    }
}
