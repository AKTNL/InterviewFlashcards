package com.interviewflashcards.files;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "source_document")
public class SourceDocument {
    @Id private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "original_filename", nullable = false) private String originalFilename;
    @Column(nullable = false, length = 64) private String sha256;
    @Column(name = "page_count", nullable = false) private int pageCount;
    @Column(name = "byte_size", nullable = false) private long byteSize;
    @Column(name = "storage_key", nullable = false) private UUID storageKey;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(nullable = false) private long version;
    protected SourceDocument() {}
    public SourceDocument(UUID userId, String name, FileStorage.Staged staged, int pages, Instant now) {
        id = UUID.randomUUID(); storageKey = UUID.randomUUID(); this.userId = userId;
        originalFilename = name; sha256 = staged.sha256(); byteSize = staged.size(); pageCount = pages; createdAt = now;
    }
    public UUID getId() { return id; }
    public UUID getStorageKey() { return storageKey; }
    public String getOriginalFilename() { return originalFilename; }
    public String getSha256() { return sha256; }
    public int getPageCount() { return pageCount; }
    public long getByteSize() { return byteSize; }
    public Instant getCreatedAt() { return createdAt; }
    public long getVersion() { return version; }
    // Version checks and writes run under MutationService's owner lock.
    public void rename(String name) { originalFilename = name; version++; }
}
