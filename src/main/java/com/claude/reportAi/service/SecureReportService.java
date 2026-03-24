package com.claude.reportAi.service;

import com.claude.reportAi.entities.GeneratedReport;
import com.claude.reportAi.repository.GeneratedReportRepository;
import com.claude.reportAi.utils.AnthropicFileCleanupClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.AnthropicSkillsResponseHelper;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class SecureReportService {

    private static final Logger log = LoggerFactory.getLogger(SecureReportService.class);

    private final AnthropicChatModel chatModel;
    private final AnthropicApi anthropicApi;
    private final GeneratedReportRepository reportRepository;

    @Autowired
    private AnthropicFileCleanupClient anthropicFileCleanupClient;

    // Mappa skill → estensione file e MIME type
    private static final Map<AnthropicApi.AnthropicSkill, FileTypeInfo> FILE_TYPES = Map.of(
            AnthropicApi.AnthropicSkill.XLSX, new FileTypeInfo(".xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            AnthropicApi.AnthropicSkill.PPTX, new FileTypeInfo(".pptx",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
            AnthropicApi.AnthropicSkill.DOCX, new FileTypeInfo(".docx",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            AnthropicApi.AnthropicSkill.PDF,  new FileTypeInfo(".pdf", "application/pdf")
    );

    public SecureReportService(AnthropicChatModel chatModel,
                               AnthropicApi anthropicApi,
                               GeneratedReportRepository reportRepository) {
        this.chatModel = chatModel;
        this.anthropicApi = anthropicApi;
        this.reportRepository = reportRepository;
    }

    /**
     * PIPELINE PRINCIPALE:
     * 1. Genera il file tramite Claude Skills
     * 2. Scarica immediatamente dal server Anthropic
     * 3. Salva nel NOSTRO database
     * 4. Cancella il file dai server Anthropic
     *
     * Il risultato: i dati dei clienti esistono SOLO sul nostro infrastruttura.
     */
    @Transactional
    public List<GeneratedReport> generateAndSecure(
            String clientId,
            String userPrompt,
            AnthropicApi.AnthropicSkill skill) {

        log.info("Inizio generazione report per cliente: {}", clientId);

        // ═══════════════════════════════════════════
        // STEP 1: Genera il file tramite Claude + Skills
        // ═══════════════════════════════════════════
        AnthropicChatOptions options = AnthropicChatOptions.builder()
                .model("claude-sonnet-4-5")
                .skill(skill)
                .maxTokens(8192)
                .build();

        ChatResponse response = chatModel.call(
                new Prompt(userPrompt, options)
        );


        String responseText = response.getResult().getOutput().getText();
        log.info("Claude ha risposto. Estrazione file IDs...");

        // ═══════════════════════════════════════════
        // STEP 2: Estrai i file_id dalla risposta
        // ═══════════════════════════════════════════
        List<String> fileIds = AnthropicSkillsResponseHelper
                .extractFileIds(response);

        if (fileIds.isEmpty()) {
            log.warn("Nessun file generato! Risposta: {}", responseText);
            throw new RuntimeException(
                    "Claude non ha generato file. Provare con un prompt più esplicito. " +
                            "Risposta: " + responseText
            );
        }

        log.info("Trovati {} file da scaricare", fileIds.size());

        List<GeneratedReport> savedReports = new ArrayList<>();

        for (int i = 0; i < fileIds.size(); i++) {
            String fileId = fileIds.get(i);

            try {
                // ═══════════════════════════════════════════
                // STEP 3: Scarica il file dal server Anthropic
                // ═══════════════════════════════════════════
                log.info("Download file {} di {} (ID: {})", i + 1, fileIds.size(), fileId);
                byte[] fileContent = anthropicApi.downloadFile(fileId);

                // ═══════════════════════════════════════════
                // STEP 4: Salva nel NOSTRO database
                // ═══════════════════════════════════════════
                FileTypeInfo typeInfo = FILE_TYPES.getOrDefault(skill,
                        new FileTypeInfo(".bin", "application/octet-stream"));

                GeneratedReport report = new GeneratedReport();
                report.setClientId(clientId);
                report.setFileName("report_" + clientId + "_" + System.currentTimeMillis()
                        + typeInfo.extension());
                report.setMimeType(typeInfo.mimeType());
                report.setSkillType(skill.name());
                report.setFileContent(fileContent);   // <-- SALVATO NEL NOSTRO DB
                report.setFileSize(fileContent.length);
                report.setPrompt(userPrompt);
                report.setResponseText(responseText);

                GeneratedReport saved = reportRepository.save(report);
                log.info("File salvato nel database con ID: {}", saved.getId());

                // ═══════════════════════════════════════════
                // STEP 5: CANCELLA il file dai server Anthropic
                // ═══════════════════════════════════════════
                try {
                    anthropicFileCleanupClient.deleteFileFromAnthropic(fileId);
                    saved.setDeletedFromAnthropic(true);
                    reportRepository.save(saved);
                    log.info("File {} CANCELLATO da Anthropic", fileId);
                } catch (Exception deleteEx) {
                    // Il file scadrà comunque dopo 24h, ma logghiamo
                    log.warn("Impossibile cancellare file {} da Anthropic: {}. " +
                                    "Il file scadrà automaticamente in 24h.",
                            fileId, deleteEx.getMessage());
                }

                savedReports.add(saved);

            } catch (Exception ex) {
                log.error("Errore nel processare file {}: {}", fileId, ex.getMessage());
                throw new RuntimeException(
                        "Errore scaricando il file da Anthropic: " + ex.getMessage(), ex);
            }
        }

        log.info("Pipeline completata: {} file salvati nel DB, " +
                "dati rimossi da Anthropic", savedReports.size());

        return savedReports;
    }

    // Record helper per i tipi file
    record FileTypeInfo(String extension, String mimeType) {}
}
