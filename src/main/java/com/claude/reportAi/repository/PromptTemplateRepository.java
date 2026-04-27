package com.claude.reportAi.repository;

import com.claude.reportAi.entities.PromptTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PromptTemplateRepository extends JpaRepository<PromptTemplate, UUID> {

    Optional<PromptTemplate> findFirstByTemplateKeyAndSectorAndActiveTrue(String templateKey, String sector);

    Optional<PromptTemplate> findFirstByTemplateKeyAndActiveTrue(String templateKey);
}
