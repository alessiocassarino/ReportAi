package com.claude.reportAi.sector;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * In-memory registry of sector profiles.
 * Adding a new sector = adding one entry here. No code changes needed elsewhere.
 */
@Component
public class SectorProfileRegistry {

    private static final List<SectorProfile> ALL = List.of(
            new SectorProfile(
                    "OIL_GAS",
                    "Oil & Gas / EPC",
                    "Contratti EPC, preventivi pipeline, confronto offerte fornitori per il settore oil & gas onshore/offshore.",
                    List.of("contract-risk-analysis", "estimate-generation", "price-comparison")
            ),
            new SectorProfile(
                    "RENEWABLE_ENERGY",
                    "Energia Rinnovabile",
                    "Contratti EPC, preventivi e confronto offerte per impianti fotovoltaici utility-scale, eolici onshore e sistemi di accumulo BESS.",
                    List.of("contract-risk-analysis", "estimate-generation", "price-comparison")
            )
    );

    private final Map<String, SectorProfile> byId = ALL.stream()
            .collect(Collectors.toMap(SectorProfile::id, Function.identity()));

    public Optional<SectorProfile> find(String sectorId) {
        return Optional.ofNullable(byId.get(sectorId));
    }

    public SectorProfile getOrDefault(String sectorId) {
        return byId.getOrDefault(sectorId, byId.get("OIL_GAS"));
    }

    public List<SectorProfile> getAll() {
        return ALL;
    }
}
