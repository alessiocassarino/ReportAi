package com.claude.reportAi.service;

import com.claude.reportAi.constant.ReportFormat;
import com.claude.reportAi.dto.ReportPlan;
import com.claude.reportAi.dto.ReportRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReportPlannerService {

    private final ChatClient claudeChatClient;

    private static final String PLANNER_SYSTEM_PROMPT = """
            Sei il planner di un sistema di reportistica enterprise.
            Il destinatario finale è un International Business Director nel settore oil & gas.

            Restituisci SOLO JSON valido con questa struttura:
            {
              "format": "TEXT | CSV | XLSX | DOCX | PPTX | PDF",
              "retrievalQuery": "string",
              "webSearchRecommended": true,
              "title": "string"
            }

            Regole:
            - TEXT: risposta libera o executive answer inline.
            - CSV: solo se l'utente chiede esplicitamente export machine-readable o dati tabellari da importare.
            - XLSX: comparazioni, pricing matrix, bid tracker, risk matrix, vendor / country scoring, dataset tabellari.
            - DOCX: executive brief, market report, memo commerciale, due diligence note, country brief.
            - PPTX: board deck, client presentation, steering committee update, negotiation pack.
            - webSearchRecommended = true solo se la richiesta dipende da informazioni esterne o aggiornate
              (mercato, prezzi, normative, sanzioni, geopolitica, competitor, news recenti).
            - retrievalQuery deve essere ottimizzata per il vector store.
            - title deve essere breve e adatto come nome file.
            - Non restituire mai AUTO.
            - Non aggiungere testo fuori dal JSON.
            """;

    public ReportPlan plan(ReportRequest request) {
        ReportFormat requestedFormat = ReportFormat.from(request.getFormat());

        try {
            ReportPlan aiPlan = claudeChatClient.prompt()
                    .system(PLANNER_SYSTEM_PROMPT)
                    .user(request.getPrompt())
                    .call()
                    .entity(ReportPlan.class);

            ReportFormat resolved = requestedFormat == ReportFormat.AUTO
                    ? defaultIfNull(aiPlan.format(), ReportFormat.TEXT)
                    : requestedFormat;

            ReportPlan finalPlan = new ReportPlan(
                    resolved,
                    hasText(aiPlan.retrievalQuery()) ? aiPlan.retrievalQuery() : request.getPrompt(),
                    aiPlan.webSearchRecommended(),
                    hasText(aiPlan.title()) ? aiPlan.title() : fallbackTitle(request.getPrompt())
            );

            log.info("Planner completato -> format={}, retrievalQuery='{}', webSearchRecommended={}, title='{}'",
                    finalPlan.format(),
                    safe(finalPlan.retrievalQuery()),
                    finalPlan.webSearchRecommended(),
                    finalPlan.title());

            return finalPlan;
        }
        catch (Exception ex) {
            log.warn("Planner fallito, uso fallback deterministico: {}", ex.getMessage());

            ReportFormat fallbackFormat = requestedFormat == ReportFormat.AUTO
                    ? ReportFormat.TEXT
                    : requestedFormat;

            return new ReportPlan(
                    fallbackFormat,
                    request.getPrompt(),
                    request.isAllowWebSearch(),
                    fallbackTitle(request.getPrompt())
            );
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private <T> T defaultIfNull(T value, T defaultValue) {
        return value != null ? value : defaultValue;
    }

    private String fallbackTitle(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return "business-report";
        }
        String normalized = prompt.replaceAll("[^a-zA-Z0-9\\s-]", " ")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase()
                .replace(" ", "-");

        if (normalized.length() > 50) {
            normalized = normalized.substring(0, 50);
        }
        return normalized.isBlank() ? "business-report" : normalized;
    }

    private String safe(String text) {
        if (text == null) {
            return null;
        }
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }
}
