package com.claude.reportAi.repository;

import com.claude.reportAi.entities.ContractAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ContractAnalysisRepository extends JpaRepository<ContractAnalysis, UUID> {
}