package com.claude.reportAi.service;

import com.claude.reportAi.entities.SystemPrompt;
import com.claude.reportAi.repository.SystemPromptRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ReportGenerationService {

    @Autowired
    private ChatClient claudeChatClient;

    @Autowired
    private SystemPromptRepository systemPromptRepository;


    public String generateReport(String userPrompt,
                                 List<Document> vectorDocs,
                                 List<String> webResults,
                                 boolean foundInKnowledgeBase,
                                 Integer systemPromptId) {

        log.info("Avvio generazione report con modello AI");
        log.info("Input generazione -> foundInKnowledgeBase={}, vectorDocsCount={}, webResultsCount={}",
                foundInKnowledgeBase,
                vectorDocs != null ? vectorDocs.size() : 0,
                webResults != null ? webResults.size() : 0);

        String knowledgeBaseContext;
        if (vectorDocs == null || vectorDocs.isEmpty()) {
            knowledgeBaseContext = "Nessuna informazione trovata nel knowledge base interno.";
            log.warn("Knowledge base context vuoto.");
        } else {
            knowledgeBaseContext = vectorDocs.stream()
                    .map(Document::getText)
                    .collect(Collectors.joining("\n\n---\n\n"));
            log.info("Knowledge base context costruito -> lunghezza={} caratteri",
                    knowledgeBaseContext.length());
        }

        String webSearchContext;
        if (webResults == null || webResults.isEmpty()) {
            webSearchContext = "Nessuna ricerca web utilizzata.";
            log.info("Nessun contesto web disponibile.");
        } else {
            webSearchContext = String.join("\n", webResults);
            log.info("Web search context costruito -> lunghezza={} caratteri",
                    webSearchContext.length());
        }

        String knowledgeBaseAvailability;
        if (foundInKnowledgeBase) {
            knowledgeBaseAvailability = "SI";
        } else {
            knowledgeBaseAvailability = "NO";
        }


        String systemPrompt = systemPromptRepository.findById(systemPromptId)
                .map(SystemPrompt::getPrompt)
                .orElse(systemPromptRepository.findDefault());

        String userMessage = """
            Prompt utente:
            %s

            Informazioni trovate nel knowledge base:
            %s

            Contesto dal knowledge base interno:
            %s

            Contesto da ricerca web:
            %s
            """.formatted(
                userPrompt,
                knowledgeBaseAvailability,
                knowledgeBaseContext,
                webSearchContext
        );

        log.info("Invocazione modello AI -> systemPromptLength={}, userMessageLength={}",
                systemPrompt.length(),
                userMessage.length());

        String response = claudeChatClient.prompt()
                .system(systemPrompt)
                .user(userMessage)
                .call()
                .content();

        log.info("Risposta modello ricevuta -> lunghezza={} caratteri",
                response != null ? response.length() : 0);
        log.debug("Anteprima risposta modello -> '{}'", safe(response));

        return response;
    }

    private String safe(String text) {
        if (text == null) {
            return null;
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() > 300 ? normalized.substring(0, 300) + "..." : normalized;
    }
}