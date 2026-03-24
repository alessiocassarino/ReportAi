package com.claude.reportAi.repository;

import com.claude.reportAi.entities.GeneratedReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GeneratedReportRepository extends JpaRepository<GeneratedReport, Long> {
    List<GeneratedReport> findByClientId(String clientId);
    List<GeneratedReport> findByDeletedFromAnthropicFalse();
}
