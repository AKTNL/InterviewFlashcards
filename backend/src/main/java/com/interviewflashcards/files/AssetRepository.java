package com.interviewflashcards.files;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssetRepository extends JpaRepository<Asset, UUID> {
    Optional<Asset> findByUserIdAndId(UUID userId, UUID id);
}
