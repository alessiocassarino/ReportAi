package com.claude.reportAi.repository;

import com.claude.reportAi.entities.PriceComparison;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface PriceComparisonRepository extends JpaRepository<PriceComparison, UUID> {
}
