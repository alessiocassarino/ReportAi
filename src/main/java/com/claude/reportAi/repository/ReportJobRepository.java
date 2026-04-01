package com.claude.reportAi.repository;

import com.claude.reportAi.entities.ReportJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ReportJobRepository extends JpaRepository<ReportJob, UUID> {
}
