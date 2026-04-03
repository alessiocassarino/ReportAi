package com.claude.reportAi.repository;

import com.claude.reportAi.entities.Estimate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface EstimateRepository extends JpaRepository<Estimate, UUID> {
}
