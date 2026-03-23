package com.claude.reportAi.service;

import com.claude.reportAi.constant.ReportFormat;
import com.claude.reportAi.dto.ReportGenerationResult;
import com.claude.reportAi.dto.ReportPlan;
import com.claude.reportAi.dto.StoredArtifact;
import com.claude.reportAi.dto.WebSearchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.AnthropicSkillsResponseHelper;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.anthropic.api.AnthropicCacheOptions;
import org.springframework.ai.anthropic.api.AnthropicCacheStrategy;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class ReportGenerationService {

    private static final int TEXT_MAX_TOKENS = 2500;
    private static final int FILE_MAX_TOKENS = 8192;
    private static final int CSV_MAX_TOKENS = 3000;

    private final ChatClient claudeChatClient;
    private final AnthropicApi anthropicApi;
    private final ReportExportService reportExportService;

    public ReportGenerationResult generate(String userPrompt,
                                           ReportPlan plan,
                                           List<Document> vectorDocs,
                                           List<WebSearchResult> webResults) {

        log.info("Avvio generazione finale -> format={}, vectorDocsCount={}, webResultsCount={}",
                plan.format(),
                vectorDocs != null ? vectorDocs.size() : 0,
                webResults != null ? webResults.size() : 0);

        return switch (plan.format()) {
            case TEXT -> generateText(userPrompt, plan, vectorDocs, webResults);
            case CSV -> generateCsv(userPrompt, plan, vectorDocs, webResults);
            case XLSX, DOCX, PPTX -> generateSkillArtifact(userPrompt, plan, vectorDocs, webResults);
            case AUTO -> throw new IllegalStateException("AUTO non è valido nello step finale");
        };
    }

    private ReportGenerationResult generateText(String userPrompt,
                                                ReportPlan plan,
                                                List<Document> vectorDocs,
                                                List<WebSearchResult> webResults) {

        ChatResponse response = claudeChatClient.prompt()
                .system(buildSystemPrompt(ReportFormat.TEXT))
                .user(buildUserMessage(userPrompt, plan, vectorDocs, webResults))
                .options(baseOptions(TEXT_MAX_TOKENS).build())
                .call()
                .chatResponse();

        String answer = extractText(response);

        return new ReportGenerationResult(
                answer,
                ReportFormat.TEXT,
                null,
                null
        );
    }

    private ReportGenerationResult generateCsv(String userPrompt,
                                               ReportPlan plan,
                                               List<Document> vectorDocs,
                                               List<WebSearchResult> webResults) {

        String userMessage = buildUserMessage(userPrompt, plan, vectorDocs, webResults) + """

                Istruzione aggiuntiva per CSV:
                - restituisci SOLO contenuto CSV valido RFC4180
                - nessun markdown
                - nessun testo introduttivo o conclusivo
                - usa una prima riga con intestazioni chiare
                - usa valori vuoti invece di inventare dati mancanti
                """;

        ChatResponse response = claudeChatClient.prompt()
                .system(buildSystemPrompt(ReportFormat.CSV))
                .user(userMessage)
                .options(baseOptions(CSV_MAX_TOKENS).temperature(0.0d).build())
                .call()
                .chatResponse();

        String csvContent = extractText(response);
        StoredArtifact artifact = reportExportService.saveTextArtifact(plan.title(), "csv", csvContent);

        return new ReportGenerationResult(
                csvContent,
                ReportFormat.CSV,
                artifact.fileName(),
                artifact.downloadUrl()
        );
    }

    private ReportGenerationResult generateSkillArtifact(String userPrompt,
                                                         ReportPlan plan,
                                                         List<Document> vectorDocs,
                                                         List<WebSearchResult> webResults) {

        String userMessage = buildUserMessage(userPrompt, plan, vectorDocs, webResults) + """

                Istruzione aggiuntiva:
                - genera il file reale usando la skill abilitata
                - non limitarti a descrivere il documento
                - il contenuto deve essere executive-ready per un International Business Director oil & gas
                """;

        AnthropicChatOptions.Builder options = baseOptions(FILE_MAX_TOKENS);

        switch (plan.format()) {
            case XLSX -> options.skill(AnthropicApi.AnthropicSkill.XLSX);
            case DOCX -> options.skill(AnthropicApi.AnthropicSkill.DOCX);
            case PPTX -> options.skill(AnthropicApi.AnthropicSkill.PPTX);
            default -> throw new IllegalStateException("Formato non supportato dalle skills: " + plan.format());
        }

        ChatResponse response = claudeChatClient.prompt()
                .system(buildSystemPrompt(plan.format()))
                .user(userMessage)
                .options(options.build())
                .call()
                .chatResponse();

        String answer = extractText(response);
        log.info("Risposta testuale Anthropic -> {}", safe(answer));
        log.info("Metadata keys -> {}", response.getMetadata() != null ? response.getMetadata().keySet() : null);

        List<String> fileIds = AnthropicSkillsResponseHelper.extractFileIds(response);

        if (fileIds == null || fileIds.isEmpty()) {
            log.warn("Nessun file generato da Anthropic. Risposta testuale: {}", safe(answer));

            return new ReportGenerationResult(
                    answer,
                    ReportFormat.TEXT,   // oppure mantieni XLSX ma con stato PARTIAL
                    null,
                    null
            );
        }

        String fileId = fileIds.get(0);
        AnthropicApi.FileMetadata metadata = anthropicApi.getFileMetadata(fileId);
        byte[] content = anthropicApi.downloadFile(fileId);

        StoredArtifact artifact = reportExportService.saveBinaryArtifact(metadata.filename(), content);

        return new ReportGenerationResult(
                answer,
                plan.format(),
                artifact.fileName(),
                artifact.downloadUrl()
        );
    }

    private AnthropicChatOptions.Builder baseOptions(int maxTokens) {
        return AnthropicChatOptions.builder()
                .model("claude-sonnet-4-5")
                .temperature(0.2d)
                .maxTokens(maxTokens)
                .cacheOptions(AnthropicCacheOptions.builder()
                        .strategy(AnthropicCacheStrategy.SYSTEM_ONLY)
                        .build());
    }

    private String buildSystemPrompt(ReportFormat format) {
        return """
                Sei un senior AI assistant per reporting strategico e commerciale.
                Il destinatario finale è un International Business Director nel settore oil & gas.

                Obiettivi tipici:
                - market intelligence
                - country entry assessment
                - partner / distributor screening
                - bid / tender strategy
                - commercial due diligence
                - negotiation brief
                - portfolio / opportunity review

                Regole:
                1. Usa prioritariamente il knowledge base interno.
                2. Usa il web solo come integrazione esterna, mai come sostituzione indiscriminata.
                3. Se i dati sono insufficienti, dichiaralo esplicitamente.
                4. Non inventare numeri, date, fonti o fatti.
                5. Evidenzia sempre impatti commerciali, rischi, opportunità e next actions.
                6. Usa tono professionale, sintetico, decision-oriented.
                7. Quando esistono conflitti tra fonti interne ed esterne, esplicitali.

                Output target: %s

                Convenzioni di formato:
                - TEXT: Executive Summary, Key Findings, Commercial Implications, Risks, Recommended Actions
                - CSV: solo CSV puro
                - XLSX: fogli e colonne utili a supportare decisioni manageriali
                - DOCX: executive brief strutturato
                - PPTX: 6-8 slide, una idea chiave per slide, taglio board-level
                """.formatted(format);
    }

    private String buildUserMessage(String userPrompt,
                                    ReportPlan plan,
                                    List<Document> vectorDocs,
                                    List<WebSearchResult> webResults) {

        String kbContext = formatKnowledgeBaseContext(vectorDocs);
        String webContext = formatWebContext(webResults);

        return """
                Richiesta utente:
                %s

                Piano:
                - formato: %s
                - retrievalQuery: %s
                - titolo suggerito: %s

                Knowledge base interno:
                %s

                Risultati web esterni:
                %s
                """.formatted(
                userPrompt,
                plan.format(),
                plan.retrievalQuery(),
                plan.title(),
                kbContext,
                webContext
        );
    }

    private String formatKnowledgeBaseContext(List<Document> vectorDocs) {
        if (vectorDocs == null || vectorDocs.isEmpty()) {
            return "Nessun documento interno rilevante trovato.";
        }

        return vectorDocs.stream()
                .limit(6)
                .map(this::formatDocument)
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    private String formatDocument(Document doc) {
        String text = doc.getText() == null ? "" : doc.getText();
        if (text.length() > 1500) {
            text = text.substring(0, 1500) + "...";
        }

        Map<String, Object> metadata = doc.getMetadata();
        String metadataText = metadata == null ? "{}" : metadata.toString();

        return """
                Metadata: %s
                Contenuto:
                %s
                """.formatted(metadataText, text);
    }

    private String formatWebContext(List<WebSearchResult> webResults) {
        if (webResults == null || webResults.isEmpty()) {
            return "Nessun risultato web usato.";
        }

        return webResults.stream()
                .limit(5)
                .map(WebSearchResult::toPromptBlock)
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    private String extractText(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }

        String text = response.getResult().getOutput().getText();
        return text != null ? text : "";
    }

    private String safe(String text) {
        if (text == null) {
            return null;
        }
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }
}