package com.interviewflashcards.files;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SourceDocumentRepository extends JpaRepository<SourceDocument, UUID> {
    Optional<SourceDocument> findByUserIdAndSha256(UUID userId, String sha256);
    Optional<SourceDocument> findByUserIdAndId(UUID userId, UUID id);
    List<SourceDocument> findAllByUserIdOrderByCreatedAtDesc(UUID userId);
}
