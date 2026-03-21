package com.claude.reportAi.repository;

import com.claude.reportAi.entities.SystemPrompt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SystemPromptRepository extends JpaRepository<SystemPrompt, Integer> {

    @Query(value = "SELECT sp.prompt FROM SystemPrompt sp " +
            "WHERE sp.name = 'DEFAULT'")
    String findDefault();
}
