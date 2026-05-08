package com.claude.reportAi.service.estimate;

import com.claude.reportAi.service.ModelChatClientFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class ProjectInfoExtractor {

    private final ModelChatClientFactory modelChatClientFactory;
    private final ObjectMapper objectMapper;

    private static final int MAX_TEXT_CHARS = 60_000;
    private static final int TAIL_CHARS     = 15_000;

    public record ProjectInfo(
            String nazione,
            String tipoProgetto,
            Double diametroPollici,
            Double lunghezzaKm,
            Integer durataMesi,
            Integer numSpread,
            Integer avanzamentoMGiorno,
            String scopeLavori,
            String zonaGeografica,
            Double pressioneProgettoBara,
            String noteTecniche
    ) {}

    private static final String SYSTEM_PROMPT = """
            <role>
            You are a technical document analyst specialized in EPC oil & gas projects.
            Your sole task is to extract structured information from the provided text.
            </role>

            <guidelines>
            - Respond ONLY with a valid JSON object, no markdown, no text before or after.
            - If a piece of information is not present in the document, use null.
            - Never fabricate or infer values not explicitly stated in the document.
            - Return numeric fields as numbers, not strings.
            - Respond in Italian for text field values where applicable.
            </guidelines>
            """;

    public ProjectInfo extract(String pdfText, String model) {
        String textChunk = buildChunkedText(pdfText);
        log.debug("ProjectInfoExtractor: testo inviato al modello {} chars (su {} totali)",
                textChunk.length(), pdfText != null ? pdfText.length() : 0);

        String userPrompt = """
                <task>
                Extract the following information from the provided technical document and return a JSON object with exactly these fields.
                The input may contain a HEAD section (beginning of the document) and a TAIL section (final pages). Both sections are relevant.
                </task>

                <output_format>
                {
                  "nazione": "paese dove si svolge il progetto",
                  "tipo_progetto": "PIPELINE o IMPIANTO o MISTO",
                  "diametro_pollici": numero decimale o null,
                  "lunghezza_km": numero decimale o null,
                  "durata_mesi": numero intero o null,
                  "num_spread": numero intero o null,
                  "avanzamento_m_giorno": numero intero (metri al giorno) o null,
                  "scope_lavori": "descrizione dello scope",
                  "zona_geografica": "descrizione della zona geografica",
                  "pressione_progetto_bara": numero decimale o null,
                  "note_tecniche": "altre note tecniche rilevanti o null"
                }
                </output_format>

                <guidelines>
                - Return ONLY the JSON object, no additional text.
                - For numeric fields, return numbers without units (e.g., 48 not "48 inches").
                - For "tipo_progetto", choose exactly one of: PIPELINE, IMPIANTO, or MISTO.
                - Use null for any field not explicitly stated in the document — do not infer.
                - If values differ between HEAD and TAIL sections, prefer the more specific/detailed value.
                </guidelines>

                <example>
                Input excerpt: "Construction of a 36-inch pipeline, 120 km, in Italy, duration 18 months, 2 spreads, design pressure 75 bara."
                Output:
                {
                  "nazione": "Italia",
                  "tipo_progetto": "PIPELINE",
                  "diametro_pollici": 36,
                  "lunghezza_km": 120,
                  "durata_mesi": 18,
                  "num_spread": 2,
                  "avanzamento_m_giorno": null,
                  "scope_lavori": null,
                  "zona_geografica": null,
                  "pressione_progetto_bara": 75,
                  "note_tecniche": null
                }
                </example>

                <input>
                %s
                </input>
                """.formatted(textChunk);

        try {
            ChatResponse response = modelChatClientFactory.call(model, SYSTEM_PROMPT, userPrompt, 2500, false);
            String json = response.getResult().getOutput().getText();
            String cleaned = cleanJson(json);

            @SuppressWarnings("unchecked")
            Map<String, Object> map = objectMapper.readValue(cleaned, Map.class);

            return new ProjectInfo(
                    getStr(map, "nazione"),
                    getStr(map, "tipo_progetto"),
                    getDouble(map, "diametro_pollici"),
                    getDouble(map, "lunghezza_km"),
                    getInt(map, "durata_mesi"),
                    getInt(map, "num_spread"),
                    getInt(map, "avanzamento_m_giorno"),
                    getStr(map, "scope_lavori"),
                    getStr(map, "zona_geografica"),
                    getDouble(map, "pressione_progetto_bara"),
                    getStr(map, "note_tecniche")
            );

        } catch (Exception e) {
            log.warn("Impossibile estrarre info progetto dal documento: {}", e.getMessage());
            return new ProjectInfo("N/D", null, null, null, null, null, null, null, null, null, null);
        }
    }

    /**
     * Returns up to MAX_TEXT_CHARS from the document head, plus the last TAIL_CHARS
     * of the document separated by a section marker. This ensures that exclusions,
     * materials specs, and clarifications — which typically appear in the final pages —
     * are not silently dropped by a simple head-only truncation.
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

    private String getStr(Map<String, Object> map, String key) {
        Object val = map.getOrDefault(key, null);
        if (val == null) return null;
        String s = val.toString().trim();
        return s.isEmpty() || s.equalsIgnoreCase("null") ? null : s;
    }

    private Double getDouble(Map<String, Object> map, String key) {
        Object val = map.getOrDefault(key, null);
        if (val == null) return null;
        try {
            return Double.parseDouble(val.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer getInt(Map<String, Object> map, String key) {
        Object val = map.getOrDefault(key, null);
        if (val == null) return null;
        try {
            return Integer.parseInt(val.toString().replaceAll("\\..*", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String cleanJson(String raw) {
        if (raw == null) return "{}";
        String s = raw.strip();
        if (s.startsWith("```")) {
            s = s.replaceAll("(?s)^```[a-z]*\\n?", "").replaceAll("```$", "").strip();
        }
        int start = s.indexOf('{');
        if (start < 0) return "{}";
        s = s.substring(start);
        int end = s.lastIndexOf('}');
        if (end >= 0) s = s.substring(0, end + 1); // taglia spazzatura dopo l'ultimo }
        s = fixBareKeys(s);                         // ripara chiavi senza valore (bug Gemini)
        if (s.endsWith("}")) return s;
        return repairTruncatedJson(s);
    }

    /**
     * Gemini restituisce occasionalmente chiavi senza valore, es. {@code "note_tecniche"} seguito da
     * {@code }} o {@code ,} senza {@code :}. Questo metodo inserisce {@code : null} in quei casi.
     */
    private String fixBareKeys(String json) {
        StringBuilder sb = new StringBuilder(json);
        int i = 0;
        while (i < sb.length()) {
            char c = sb.charAt(i);
            if (c != '"') { i++; continue; }

            int strStart = i++;
            // Scansiona fino alla virgoletta chiudente
            while (i < sb.length()) {
                char sc = sb.charAt(i++);
                if (sc == '\\') { i++; }      // salta escape
                else if (sc == '"') break;
            }
            int strEnd = i; // indice dopo la virgoletta chiudente

            // Salta whitespace
            int wsEnd = i;
            while (wsEnd < sb.length() && Character.isWhitespace(sb.charAt(wsEnd))) wsEnd++;
            if (wsEnd >= sb.length()) break;

            char next = sb.charAt(wsEnd);
            if (next == '}' || next == ',') {
                // Chiave bare: verifica che il char precedente la stringa sia { o ,
                String before = sb.substring(0, strStart).stripTrailing();
                if (!before.isEmpty()) {
                    char prev = before.charAt(before.length() - 1);
                    if (prev == '{' || prev == ',') {
                        sb.insert(strEnd, ": null");
                        i = strEnd + 6; // riposiziona dopo ": null"
                        continue;
                    }
                }
            }
        }
        return sb.toString();
    }

    private String repairTruncatedJson(String json) {
        StringBuilder sb = new StringBuilder(json.stripTrailing());
        if (!sb.isEmpty() && sb.charAt(sb.length() - 1) == ',') {
            sb.deleteCharAt(sb.length() - 1);
        }
        java.util.Deque<Character> stack = new java.util.ArrayDeque<>();
        boolean inString = false, escaped = false;
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\' && inString) { escaped = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (!inString) {
                if (c == '{') stack.push('}');
                else if (c == '[') stack.push(']');
                else if ((c == '}' || c == ']') && !stack.isEmpty()) stack.pop();
            }
        }
        if (inString) sb.append('"');
        while (!stack.isEmpty()) sb.append(stack.pop());
        return sb.toString();
    }
}
