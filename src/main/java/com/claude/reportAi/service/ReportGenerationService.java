package com.claude.reportAi.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class ReportGenerationService {

    private final ChatClient claudeChatClient;

    public ReportGenerationService(ChatClient claudeChatClient) {
        this.claudeChatClient = claudeChatClient;
    }

    public String generateReport(String userPrompt,
                                 List<Document> vectorDocs,
                                 List<String> webResults,
                                 boolean foundInKnowledgeBase) {

        String knowledgeBaseContext;
        if (vectorDocs == null || vectorDocs.isEmpty()) {
            knowledgeBaseContext = "Nessuna informazione trovata nel knowledge base interno.";
        } else {
            knowledgeBaseContext = vectorDocs.stream()
                    .map(Document::getText)
                    .collect(Collectors.joining("\n\n---\n\n"));
        }

        String webSearchContext;
        if (webResults == null || webResults.isEmpty()) {
            webSearchContext = "Nessuna ricerca web utilizzata.";
        } else {
            webSearchContext = String.join("\n", webResults);
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

        return claudeChatClient.prompt()
                .system(systemPrompt)
                .user(userMessage)
                .call()
                .content();
    }
}