package com.claude.reportAi.repository;

import com.claude.reportAi.entities.StoredFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface StoredFileRepository extends JpaRepository<StoredFile, UUID> {
    Optional<StoredFile> findBySha256(String sha256);

}
