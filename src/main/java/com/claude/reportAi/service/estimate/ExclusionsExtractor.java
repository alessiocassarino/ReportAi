package com.claude.reportAi.service.estimate;

import com.claude.reportAi.service.ModelChatClientFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Extracts explicit exclusions, battery limits, and client-furnished items from an EPC/EPCC
 * Scope of Work document. The result is injected into the estimate prompt as a binding
 * constraint to prevent the model from including excluded items as base costs.
 *
 * Uses the same head+tail chunking strategy as ProjectInfoExtractor to ensure that
 * exclusions stated in the final pages of the document are not missed.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExclusionsExtractor {

    private final ModelChatClientFactory modelChatClientFactory;
    private final ObjectMapper objectMapper;

    private static final int MAX_TEXT_CHARS = 60_000;
    private static final int TAIL_CHARS     = 20_000;

    public record ExclusionContext(
            boolean fuelByClient,
            boolean securityExcluded,
            boolean hddMajorExcluded,
            boolean chemicalInjectionExcluded,
            boolean permitsExcluded,
            boolean satFatExcluded,
            List<String> otherExclusions,
            List<String> clientSuppliedItems,
            List<String> itemsToQuoteSeparately,
            List<String> batteryLimits
    ) {
        public boolean hasAnyExclusion() {
            return fuelByClient || securityExcluded || hddMajorExcluded
                    || chemicalInjectionExcluded || permitsExcluded || satFatExcluded
                    || !otherExclusions.isEmpty() || !clientSuppliedItems.isEmpty()
                    || !itemsToQuoteSeparately.isEmpty() || !batteryLimits.isEmpty();
        }

        public static ExclusionContext empty() {
            return new ExclusionContext(false, false, false, false, false, false,
                    List.of(), List.of(), List.of(), List.of());
        }
    }

    private static final String SYSTEM_PROMPT = """
            <role>
            You are a technical document analyst specialized in EPC oil & gas contracts and Scope of Work documents.
            Your task is to identify and extract all exclusions, battery limits, client-furnished items, and items to be quoted separately.
            </role>

            <guidelines>
            - Respond ONLY with a valid JSON object, no markdown, no text before or after.
            - Extract ONLY items that are explicitly stated as excluded, client-supplied, or out of scope.
            - Do NOT infer exclusions — only extract what is clearly stated in the document.
            - For boolean fields: set to true only if the document explicitly excludes the item or assigns that responsibility to COMPANY/Client.
            - All text values in Italian.
            - If a field has no applicable content, use false (for booleans) or [] (for arrays).
            </guidelines>
            """;

    public ExclusionContext extract(String pdfText, String model) {
        String textChunk = buildChunkedText(pdfText);
        log.debug("ExclusionsExtractor: testo inviato al modello {} chars (su {} totali)",
                textChunk.length(), pdfText != null ? pdfText.length() : 0);

        String userPrompt = """
                <task>
                Analyze the provided EPC/EPCC Scope of Work document and extract all exclusions, battery limits,
                and client-supplied items. Focus especially on sections titled: "Exclusions", "Battery Limits",
                "COMPANY-Furnished Items", "Client Responsibilities", "Notes and Clarifications", "Assumptions".
                The input may contain a HEAD section (beginning) and a TAIL section (final pages) — check both.
                </task>

                <output_format>
                {
                  "fuel_by_client": true/false,
                  "security_excluded": true/false,
                  "hdd_major_excluded": true/false,
                  "chemical_injection_excluded": true/false,
                  "permits_excluded": true/false,
                  "sat_fat_excluded": true/false,
                  "other_exclusions": ["<item explicitly excluded from contractor scope>"],
                  "client_supplied_items": ["<item furnished or supplied by COMPANY or Client>"],
                  "items_to_quote_separately": ["<item explicitly stated as to be priced or quoted separately>"],
                  "battery_limits": ["<battery limit description or boundary condition>"]
                }
                </output_format>

                <guidelines>
                - fuel_by_client: true if fuel for construction/transport equipment is provided by COMPANY/Client.
                - security_excluded: true if security, guarding, or escort services are excluded from Contractor scope.
                - hdd_major_excluded: true if major HDD/horizontal directional drilling crossings are excluded or priced separately.
                - chemical_injection_excluded: true if chemical injection packages are excluded.
                - permits_excluded: true if permits, authorizations, or right-of-way are under Client/End User responsibility.
                - sat_fat_excluded: true if SAT/FAT client attendance, training, or 2-year spare parts are excluded.
                - other_exclusions: every other distinct item explicitly excluded from Contractor scope.
                - client_supplied_items: every item furnished or supplied by COMPANY or Client to the Contractor.
                - items_to_quote_separately: items that must be priced in a separate offer or allowance.
                - battery_limits: explicit boundary descriptions (e.g., "battery limit at flange on existing 36-inch header").
                - Copy key exclusion phrases verbatim when useful for traceability.
                </guidelines>

                <example>
                Input: "The following are excluded from Contractor scope: security and guarding services, fuel for
                construction equipment (furnished by COMPANY), major HDD crossings (separate quotation required),
                chemical injection package. Permits and authorizations are End User responsibility."
                Output:
                {
                  "fuel_by_client": true,
                  "security_excluded": true,
                  "hdd_major_excluded": true,
                  "chemical_injection_excluded": true,
                  "permits_excluded": true,
                  "sat_fat_excluded": false,
                  "other_exclusions": [],
                  "client_supplied_items": ["Fuel per equipment di costruzione e trasporto"],
                  "items_to_quote_separately": ["Major HDD crossings"],
                  "battery_limits": []
                }
                </example>

                <input>
                %s
                </input>
                """.formatted(textChunk);

        try {
            ChatResponse response = modelChatClientFactory.call(model, SYSTEM_PROMPT, userPrompt, 2000, false);
            String json = response.getResult().getOutput().getText();
            String cleaned = cleanJson(json);

            @SuppressWarnings("unchecked")
            Map<String, Object> map = objectMapper.readValue(cleaned, Map.class);

            ExclusionContext ctx = new ExclusionContext(
                    getBool(map, "fuel_by_client"),
                    getBool(map, "security_excluded"),
                    getBool(map, "hdd_major_excluded"),
                    getBool(map, "chemical_injection_excluded"),
                    getBool(map, "permits_excluded"),
                    getBool(map, "sat_fat_excluded"),
                    getStringList(map, "other_exclusions"),
                    getStringList(map, "client_supplied_items"),
                    getStringList(map, "items_to_quote_separately"),
                    getStringList(map, "battery_limits")
            );

            log.info("ExclusionsExtractor: fuel_client={}, security={}, hdd_major={}, chemical={}, permits={}, "
                            + "sat_fat={}, altre={}, client_items={}, quota_sep={}, battery_limits={}",
                    ctx.fuelByClient(), ctx.securityExcluded(), ctx.hddMajorExcluded(),
                    ctx.chemicalInjectionExcluded(), ctx.permitsExcluded(), ctx.satFatExcluded(),
                    ctx.otherExclusions().size(), ctx.clientSuppliedItems().size(),
                    ctx.itemsToQuoteSeparately().size(), ctx.batteryLimits().size());

            return ctx;

        } catch (Exception e) {
            log.warn("Impossibile estrarre exclusions dal documento: {}", e.getMessage());
            return ExclusionContext.empty();
        }
    }

    /**
     * Returns up to MAX_TEXT_CHARS from the document head plus the last TAIL_CHARS.
     * Exclusions and battery limits often appear in the final sections of EPC documents.
     */
    private String buildChunkedText(String pdfText) {
        if (pdfText == null) return "";
        if (pdfText.length() <= MAX_TEXT_CHARS) return pdfText;
        String head = pdfText.substring(0, MAX_TEXT_CHARS);
        int tailStart = Math.max(MAX_TEXT_CHARS, pdfText.length() - TAIL_CHARS);
        if (tailStart >= pdfText.length()) return head;
        String tail = pdfText.substring(tailStart);
        return head + "\n\n[=== DOCUMENT TAIL — FINAL SECTIONS ===]\n\n" + tail;
    }

    private boolean getBool(Map<String, Object> map, String key) {
        Object val = map.getOrDefault(key, null);
        if (val == null) return false;
        if (val instanceof Boolean b) return b;
        return Boolean.parseBoolean(val.toString());
    }

    @SuppressWarnings("unchecked")
    private List<String> getStringList(Map<String, Object> map, String key) {
        Object val = map.getOrDefault(key, null);
        if (!(val instanceof List<?> list)) return List.of();
        return list.stream()
                .filter(item -> item != null)
                .map(Object::toString)
                .filter(s -> !s.isBlank())
                .toList();
    }

    private String cleanJson(String raw) {
        if (raw == null) return "{}";
        String s = raw.strip();
        if (s.startsWith("```")) {
            s = s.replaceAll("(?s)^```[a-zA-Z]*\\n?", "").replaceAll("```\\s*$", "").strip();
        }
        int start = s.indexOf('{');
        if (start < 0) return "{}";
        s = s.substring(start);
        int end = s.lastIndexOf('}');
        if (end >= 0) s = s.substring(0, end + 1);
        return s;
    }
}
