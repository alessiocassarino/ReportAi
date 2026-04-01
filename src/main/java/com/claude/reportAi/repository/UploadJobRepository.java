package com.claude.reportAi.repository;

import com.claude.reportAi.entities.UploadJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface UploadJobRepository extends JpaRepository<UploadJob, UUID> {
}
