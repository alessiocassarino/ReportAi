package com.claude.reportAi.service.report;

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

    private static final int MAX_TEXT_CHARS = 30_000;

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
            Sei un analista di documenti tecnici specializzato in progetti EPC oil & gas.
            Il tuo unico compito è estrarre informazioni strutturate dal testo fornito.
            Rispondi ESCLUSIVAMENTE con un oggetto JSON valido, senza markdown, senza testo prima o dopo.
            Se un'informazione non è presente nel documento usa null.
            """;

    public ProjectInfo extract(String pdfText, String model) {
        String truncated = pdfText != null && pdfText.length() > MAX_TEXT_CHARS
                ? pdfText.substring(0, MAX_TEXT_CHARS)
                : pdfText;

        String userPrompt = """
                Estrai le seguenti informazioni dal documento tecnico fornito e restituisci un JSON con esattamente questi campi:
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

                Documento:
                ---
                %s
                ---
                """.formatted(truncated);

        try {
            ChatResponse response = modelChatClientFactory.call(model, SYSTEM_PROMPT, userPrompt, 1500, false);
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
        if (end > 0 && end == s.length() - 1) {
            return s;
        }
        return repairTruncatedJson(s);
    }

    private String repairTruncatedJson(String json) {
        StringBuilder sb = new StringBuilder(json.stripTrailing());
        if (!sb.isEmpty() && sb.charAt(sb.length() - 1) == ',') {
            sb.deleteCharAt(sb.length() - 1);
        }
        int braces = 0, brackets = 0;
        boolean inString = false, escaped = false;
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\' && inString) { escaped = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (!inString) {
                switch (c) {
                    case '{' -> braces++;
                    case '}' -> braces--;
                    case '[' -> brackets++;
                    case ']' -> brackets--;
                }
            }
        }
        if (inString) sb.append('"');
        for (int i = 0; i < Math.max(0, brackets); i++) sb.append(']');
        for (int i = 0; i < Math.max(0, braces);   i++) sb.append('}');
        return sb.toString();
    }
}
