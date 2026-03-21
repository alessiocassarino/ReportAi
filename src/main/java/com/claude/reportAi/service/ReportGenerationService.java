package com.claude.reportAi.service;

import com.claude.reportAi.dto.AssembledContext;
import com.claude.reportAi.dto.OutputValidationResult;
import com.claude.reportAi.entities.SystemPrompt;
import com.claude.reportAi.repository.SystemPromptRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReportGenerationService {

    private final ChatClient claudeChatClient;
    private final SystemPromptRepository systemPromptRepository;
    private final OutputValidationService outputValidationService;

    public String generateReport(String userPrompt,
                                 AssembledContext context,
                                 boolean foundInKnowledgeBase,
                                 Integer systemPromptId) {

        log.info("Avvio generazione report con modello AI");

        String kbContext = renderDocuments(
                context != null ? context.getKnowledgeBaseDocuments() : List.of(),
                "Nessuna informazione trovata nel knowledge base interno."
        );

        String tempContext = renderDocuments(
                context != null ? context.getTemporaryDocuments() : List.of(),
                "Nessun documento temporaneo allegato."
        );

        String referenceDataContext = renderDocuments(
                context != null ? context.getReferenceDataDocuments() : List.of(),
                "Nessun file di reference data disponibile."
        );

        String webSearchContext;
        if (context == null || context.getWebResults() == null || context.getWebResults().isEmpty()) {
            webSearchContext = "Nessuna ricerca web utilizzata.";
        } else {
            webSearchContext = String.join("\n\n", context.getWebResults());
        }

        String templateDescription = context != null && context.getTemplateDescription() != null
                ? context.getTemplateDescription()
                : "Nessun template selezionato.";

        String knowledgeBaseAvailability = foundInKnowledgeBase ? "SI" : "NO";

        String systemPrompt = systemPromptRepository.findById(systemPromptId)
                .map(SystemPrompt::getPrompt)
                .orElse(systemPromptRepository.findDefault());

        // NEW: Add warning if no context
        if (!foundInKnowledgeBase && (context == null || context.getWebResults().isEmpty())) {
            systemPrompt = systemPrompt + "\n\n⚠️ CRITICAL: You have NO external context for this query. "
                    + "Do NOT fabricate data. Return ONLY what you know for certain, "
                    + "or explicitly state what information is missing.";
            log.warn("Generating report WITHOUT external context");
        }

        String userMessage = """
            Prompt utente:
            %s

            Informazioni trovate nel knowledge base:
            %s

            Contesto dal knowledge base interno:
            %s

            Contesto dai documenti temporanei allegati:
            %s

            Contesto da reference data allegata:
            %s

            Informazioni template selezionato:
            %s

            Contesto da ricerca web:
            %s
            """.formatted(
                userPrompt,
                knowledgeBaseAvailability,
                kbContext,
                tempContext,
                referenceDataContext,
                templateDescription,
                webSearchContext
        );

        log.info("Invocazione modello AI -> systemPromptLength={}, userMessageLength={}, contextAvailable={}",
                systemPrompt != null ? systemPrompt.length() : 0,
                userMessage.length(),
                foundInKnowledgeBase);

        String response = claudeChatClient.prompt()
                .system(systemPrompt)
                .user(userMessage)
                .call()
                .content();

        log.info("Risposta modello ricevuta -> lunghezza={} caratteri",
                response != null ? response.length() : 0);

        // NEW: Validate output
        OutputValidationResult validationResult = outputValidationService.validate(
                response,
                foundInKnowledgeBase
        );

        if (!validationResult.isAcceptable() && validationResult.isCritical()) {
            log.error("CRITICAL: Output quality below acceptable threshold -> score={}, regenerating...",
                    validationResult.getQualityScore());

            // RETRY: Invoke Claude again with enhanced prompt
            String enhancedSystemPrompt = systemPrompt + 
                    "\n\n[PREVIOUS RESPONSE HAD QUALITY ISSUES - PLEASE REGENERATE WITH HIGHER ACCURACY]";

            response = claudeChatClient.prompt()
                    .system(enhancedSystemPrompt)
                    .user(userMessage)
                    .call()
                    .content();

            log.info("Report regenerated after quality check -> newLength={}", 
                    response != null ? response.length() : 0);
        }

        return response;
    }

    private String renderDocuments(List<Document> documents, String fallback) {
        if (documents == null || documents.isEmpty()) {
            return fallback;
        }

        return documents.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n---\n\n"));
    }
}
