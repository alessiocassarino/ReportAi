package com.claude.reportAi.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ReportGenerationService {

    private final ChatClient claudeChatClient;

    public ReportGenerationService(ChatClient claudeChatClient) {
        this.claudeChatClient = claudeChatClient;
    }

    public String generateReport(String userPrompt,
                                 List<Document> vectorDocs,
                                 List<String> webResults,
                                 boolean foundInKnowledgeBase) {

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

        String systemPrompt = """
            Sei un assistente professionale per la generazione di report aziendali.
            Rispondi in italiano, con tono professionale, chiaro, dettagliato e strutturato.
            Regole:
            - Usa prioritariamente il knowledge base interno.
            - Se il knowledge base non contiene informazioni sufficienti, dichiaralo esplicitamente.
            - Se sono presenti risultati web, usali come integrazione esterna.
            - Non inventare dati mancanti.
            - Organizza la risposta in: sintesi, dettagli, osservazioni, conclusioni.
            - Se richiesto un report tabellare, prepara i dati in modo compatibile con CSV/XLSX.
            """;

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