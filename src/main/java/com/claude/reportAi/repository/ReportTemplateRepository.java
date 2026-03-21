package com.claude.reportAi.repository;

import com.claude.reportAi.entities.ReportTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReportTemplateRepository extends JpaRepository<ReportTemplate, Long> {

    List<ReportTemplate> findByActiveTrueOrderByNameAsc();
}