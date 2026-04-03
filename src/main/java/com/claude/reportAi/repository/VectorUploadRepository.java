package com.claude.reportAi.repository;

import com.claude.reportAi.entities.VectoreUpload;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface VectorUploadRepository extends JpaRepository<VectoreUpload, UUID> {
}
