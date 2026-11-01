package com.interviewflashcards.files;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SourceDocumentRepository extends JpaRepository<SourceDocument, UUID> {
    @org.springframework.data.jpa.repository.Query(value="select * from source_document where user_id=:userId and sha256=:sha256 and not shared_copy", nativeQuery=true)
    Optional<SourceDocument> findByUserIdAndSha256(UUID userId, String sha256);
    Optional<SourceDocument> findByUserIdAndId(UUID userId, UUID id);
    List<SourceDocument> findAllByUserIdOrderByCreatedAtDesc(UUID userId);
}
