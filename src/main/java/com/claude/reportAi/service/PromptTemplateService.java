package com.claude.reportAi.service;

import com.claude.reportAi.repository.PromptTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Resolves LLM prompt bodies by templateKey + sector.
 *
 * Resolution order:
 *  1. Exact match: templateKey + sector + active=true
 *  2. Fallback: templateKey + "OIL_GAS" (base templates always exist)
 *  3. If neither found: throws IllegalStateException so a missing seed is caught early.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PromptTemplateService {

    private final PromptTemplateRepository repository;

    /**
     * Returns the active prompt body for the given key and sector.
     * Falls back to OIL_GAS template when no sector-specific version exists.
     */
    public String resolve(String templateKey, String sector) {
        // 1. Sector-specific prompt
        var found = repository.findFirstByTemplateKeyAndSectorAndActiveTrue(templateKey, sector);
        if (found.isPresent()) {
            log.debug("Prompt resolved: key={} sector={} (sector-specific)", templateKey, sector);
            return found.get().getBody();
        }

        // 2. OIL_GAS fallback (always seeded)
        if (!"OIL_GAS".equals(sector)) {
            var fallback = repository.findFirstByTemplateKeyAndSectorAndActiveTrue(templateKey, "OIL_GAS");
            if (fallback.isPresent()) {
                log.warn("Prompt sector-specific non trovato per key={} sector={} — uso fallback OIL_GAS", templateKey, sector);
                return fallback.get().getBody();
            }
        }

        throw new IllegalStateException(
                "Nessun prompt attivo trovato per templateKey='" + templateKey + "' sector='" + sector + "'. "
                + "Verifica la migrazione V020.");
    }

    /**
     * Convenience overload defaulting to OIL_GAS — used by v1 processors.
     */
    public String resolve(String templateKey) {
        return resolve(templateKey, "OIL_GAS");
    }
}
