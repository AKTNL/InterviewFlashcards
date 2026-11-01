package com.interviewflashcards.files;

import static org.assertj.core.api.Assertions.*;
import com.interviewflashcards.common.config.AppProperties;
import com.interviewflashcards.common.web.ApiFailure;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalFileStorageTest {
    @TempDir Path root;
    private AppProperties properties(long limit, Path temporary) {
        return new AppProperties("Asia/Shanghai", new AppProperties.Storage(root.resolve("files").toString(), temporary.toString(), limit),
                new AppProperties.Account("unused", "unused"));
    }
    @Test void streamLimitCleansPartialFilesAndPublishedContentKeepsDigest() throws Exception {
        Path temporary = root.resolve("tmp");
        var storage = new LocalFileStorage(properties(3, temporary));
        assertThatThrownBy(() -> storage.stage(new ByteArrayInputStream(new byte[4])))
                .isInstanceOf(ApiFailure.class).hasMessage("FILE_TOO_LARGE");
        try (var files = Files.list(temporary)) { assertThat(files).isEmpty(); }
        UUID key = UUID.randomUUID();
        try (var staged = storage.stage(new ByteArrayInputStream("abc".getBytes()))) {
            assertThat(staged.sha256()).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
            storage.publish(staged, key);
        }
        assertThat(storage.read(key).getContentAsByteArray()).isEqualTo("abc".getBytes());
        try (var files = Files.list(temporary)) { assertThat(files).isEmpty(); }
    }
    @Test void rejectsOverlappingStorageRoots() {
        assertThatThrownBy(() -> new LocalFileStorage(properties(3, root.resolve("files/tmp"))))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test void globalCapacityCountsStagingAndUnreferencedFilesBeforePublishing() throws Exception {
        Path temporary=root.resolve("tmp");var storage=new LocalFileStorage(properties(3,temporary));
        storage.capacity(new CapacityProperties(100,20,2,2,20,8,1));
        Files.writeString(root.resolve("files").resolve(UUID.randomUUID().toString()),"old");
        try(var staged=storage.stage(new ByteArrayInputStream("abc".getBytes()))) {
            assertThatThrownBy(()->storage.publish(staged,UUID.randomUUID())).isInstanceOf(ApiFailure.class).hasMessage("CAPACITY_EXCEEDED");
        }
        try(var files=Files.list(temporary)) {assertThat(files).isEmpty();}
    }
    @Test void startupRemovesOnlyInterruptedPartsAndKeepsPublishedResources() throws Exception {
        Path temporary = Files.createDirectories(root.resolve("tmp"));
        Path files = Files.createDirectories(root.resolve("files"));
        UUID key = UUID.randomUUID();
        Files.writeString(files.resolve(key.toString()), "committed PDF or backup");
        Files.writeString(files.resolve(key + ".part"), "interrupted publish");
        Files.writeString(temporary.resolve("upload-123.part"), "interrupted upload");
        Files.writeString(temporary.resolve("keep.part"), "unrelated");
        Files.createDirectories(temporary.resolve("upload-456.part"));
        Files.writeString(temporary.resolve("upload-456.part/keep"), "not a staging file");
        var storage = new LocalFileStorage(properties(100, temporary));
        assertThat(storage.read(key).getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("committed PDF or backup");
        assertThat(files.resolve(key + ".part")).doesNotExist();
        assertThat(temporary.resolve("upload-123.part")).doesNotExist();
        assertThat(temporary.resolve("keep.part")).hasContent("unrelated");
        assertThat(temporary.resolve("upload-456.part/keep")).hasContent("not a staging file");
    }
}
