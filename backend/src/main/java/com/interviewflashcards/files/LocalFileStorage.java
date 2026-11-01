package com.interviewflashcards.files;

import com.interviewflashcards.common.config.AppProperties;
import com.interviewflashcards.common.web.ApiFailure;
import com.interviewflashcards.common.web.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

@Component
public class LocalFileStorage implements FileStorage {
    private final Path root;
    private final Path temporary;
    private final long maxBytes;
    private CapacityProperties capacity = new CapacityProperties(536870912L,20,2,2,20,5368709120L,536870912L);
    @org.springframework.beans.factory.annotation.Autowired
    public void capacity(CapacityProperties limits) { this.capacity=limits; }
    private void checkDisk(long incoming) throws IOException {
        long total=0;
        for(Path folder:java.util.List.of(root,temporary)) {
            try(var paths=Files.walk(folder)) {
                for(Path path:paths.filter(p->Files.isRegularFile(p,java.nio.file.LinkOption.NOFOLLOW_LINKS)).toList()) {
                    try { total=Math.addExact(total,Files.size(path)); } catch(java.nio.file.NoSuchFileException ignored) { }
                }
            }
            if(Files.getFileStore(folder).getUsableSpace()<capacity.reserveBytes()+incoming) throw new ApiFailure(ErrorCode.CAPACITY_EXCEEDED);
        }
        if(incoming>capacity.globalBytes() || total>capacity.globalBytes()-incoming) throw new ApiFailure(ErrorCode.CAPACITY_EXCEEDED);
    }
    @Override public synchronized void checkSpace(long incoming) throws IOException { checkDisk(incoming); }
    public LocalFileStorage(AppProperties properties) throws IOException {
        root = Path.of(properties.storage().root()).toAbsolutePath().normalize();
        temporary = Path.of(properties.storage().temporaryRoot()).toAbsolutePath().normalize();
        if (root.startsWith(temporary) || temporary.startsWith(root)) {
            throw new IllegalStateException("Storage and temporary directories must be separate");
        }
        Files.createDirectories(root);
        Files.createDirectories(temporary);
        if (root.toRealPath().startsWith(temporary.toRealPath()) || temporary.toRealPath().startsWith(root.toRealPath())) {
            throw new IllegalStateException("Storage and temporary directories must be separate");
        }
        maxBytes = properties.storage().maxBytes();
        // Single-JVM startup, before this storage bean can accept uploads. Never run
        // periodically: an in-flight upload may legitimately own a .part file.
        removeInterruptedParts(temporary, "upload-[0-9]+\\.part");
        removeInterruptedParts(root, "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.part");
    }
    private static void removeInterruptedParts(Path directory, String pattern) throws IOException {
        int count = 0;
        try (var paths = Files.list(directory)) {
            for (var path : paths.filter(p -> p.getFileName().toString().matches(pattern)
                    && Files.isRegularFile(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)).toList()) {
                Files.delete(path);
                count++;
            }
        }
        if (count > 0) LoggerFactory.getLogger(LocalFileStorage.class).info("Interrupted storage staging cleaned count={}", count);
    }
    @Override public Staged stage(InputStream input) throws IOException {
        checkDisk(maxBytes);
        Path path = Files.createTempFile(temporary, "upload-", ".part");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size = 0;
            try (var output = Files.newOutputStream(path)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    size += count;
                    if (size % (8*1024*1024) < count) checkDisk(count);
                    if (size > maxBytes) throw new ApiFailure(ErrorCode.FILE_TOO_LARGE);
                    digest.update(buffer, 0, count);
                    output.write(buffer, 0, count);
                }
            }
            return new Staged(path, HexFormat.of().formatHex(digest.digest()), size);
        } catch (IOException | RuntimeException | NoSuchAlgorithmException exception) {
            Files.deleteIfExists(path);
            if (exception instanceof NoSuchAlgorithmException) throw new IllegalStateException(exception);
            if (exception instanceof IOException io) throw io;
            throw (RuntimeException) exception;
        }
    }
    @Override public synchronized void publish(Staged staged, UUID key) throws IOException {
        checkDisk(staged.size());
        // UUIDs are generated by the server. User filenames never enter storage paths.
        // Temp and permanent storage may be separate volumes: copy to a sibling then rename.
        Path publishing = root.resolve(key + ".part");
        try {
            Files.copy(staged.path(), publishing);
            Files.move(publishing, root.resolve(key.toString()), java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(publishing); }
    }
    @Override public Resource read(UUID key) {
        Path file = root.resolve(key.toString());
        if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(file)) {
            throw new ApiFailure(ErrorCode.STORAGE_UNAVAILABLE);
        }
        return new FileSystemResource(file);
    }
    @Override public void delete(UUID key) {
        try { Files.deleteIfExists(root.resolve(key.toString())); }
        catch (IOException exception) { LoggerFactory.getLogger(getClass()).warn("storage cleanup failed"); }
    }
    @Override public boolean reclaim(UUID key) {
        try {
            Files.deleteIfExists(root.resolve(key.toString()));
            return Files.notExists(root.resolve(key.toString()), java.nio.file.LinkOption.NOFOLLOW_LINKS);
        } catch (IOException exception) {
            LoggerFactory.getLogger(getClass()).warn("storage reclamation failed");
            return false;
        }
    }
}
