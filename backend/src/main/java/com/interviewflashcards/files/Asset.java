package com.interviewflashcards.files;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "asset")
public class Asset {
    @Id private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "source_id", nullable = false) private UUID sourceId;
    @Column(name = "storage_key", nullable = false) private UUID storageKey;
    @Column(name = "media_type", nullable = false, length = 40) private String mediaType;
    @Column(name = "byte_size", nullable = false) private long byteSize;
    @Column(nullable = false, length = 64) private String sha256;
    protected Asset() {}
    public UUID getStorageKey() { return storageKey; }
    public String getMediaType() { return mediaType; }
}
