package com.claude.reportAi.repository;

import com.claude.reportAi.entities.DocumentMetadata;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface DocumentMetadataRepository extends JpaRepository<DocumentMetadata, UUID> {
}
