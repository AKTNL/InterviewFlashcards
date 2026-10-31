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
}
