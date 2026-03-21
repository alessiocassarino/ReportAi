package com.claude.reportAi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/**
 * 📤 DTO per la risposta della generazione report
 *
 * ReportResponse rappresenta la risposta HTTP che il server restituisce
 * dopo che l'endpoint POST /api/reports/generate ha elaborato la richiesta.
 *
 * Contiene informazioni complete sul report generato incluso il contenuto,
 * il file generato (se applicabile) e i metadati di esecuzione.
 *
 * Struttura della risposta:
 * - status: Stato della generazione (OK, ERROR, PARTIAL_SUCCESS)
 * - foundInKnowledgeBase: Se sono stati trovati documenti nel KB locale
 * - webSearchUsed: Se è stata eseguita una ricerca web
 * - answer: Contenuto completo del report generato
 * - fileName: Nome del file esportato (se applicabile)
 * - downloadUrl: URL per scaricare il file
 * - metadata: Metriche di esecuzione e qualità
 *
 * Esempio di risposta completa:
 * {@code
 * {
 *   "status": "OK",
 *   "foundInKnowledgeBase": true,
 *   "webSearchUsed": false,
 *   "answer": "# Report Q4 2024\n\n...",
 *   "fileName": "550e8400-e29b-41d4-a716-446655440000.xlsx",
 *   "downloadUrl": "/api/reports/download/550e8400-e29b-41d4-a716-446655440000.xlsx",
 *   "metadata": {
 *     "executionTimeMs": 3245,
 *     "outputQualityScore": 0.92,
 *     ...
 *   }
 * }
 * }
 *
 * @author ReportAI Team
 * @version 1.2.0
 * @since 2025-01-15
 */
@Data
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReportResponse {

    /**
     * ✅ Stato della generazione del report
     *
     * Possibili valori:
     * - "OK": Generazione completata con successo
     * - "PARTIAL_SUCCESS": Generazione parziale (alcuni dati mancanti)
     * - "ERROR": Errore durante la generazione
     *
     * Se status è "ERROR", controllare il campo metadata per i dettagli
     */
    private String status;

    /**
     * 🗂️ Indica se documenti sono stati trovati nel knowledge base locale
     *
     * - true: Il vector store ha trovato documenti rilevanti
     * - false: Nessun documento trovato nel KB (possibile web search)
     *
     * Utile per capire la qualità e la fonte del contesto usato
     * nella generazione del report.
     */
    private boolean foundInKnowledgeBase;

    /**
     * 🌐 Indica se è stata eseguita una ricerca web
     *
     * - true: Quando KB era vuoto e web search è stato abilitato
     * - false: KB aveva dati sufficienti o web search è disabilitato
     *
     * Se true, il report contiene informazioni da fonti esterne.
     */
    private boolean webSearchUsed;

    /**
     * 📝 Contenuto completo del report generato
     *
     * Contiene il testo del report in formato Markdown:
     * - Titoli con # ## ###
     * - Liste con * - •
     * - Tabelle con | |
     * - Paragrafi normali
     * - Link con [testo](url)
     *
     * Lunghezza: solitamente 500-5000 caratteri
     * Formato: Markdown strutturato
     * Encoding: UTF-8
     *
     * Questo campo è SEMPRE presente, anche se fileName è null
     */
    private String answer;

    /**
     * 💾 Nome file esportato (se applicabile)
     *
     * Presente solo se format non era JSON
     * Formato: UUID.estensione (es: "550e8400-e29b-41d4-a716-446655440000.xlsx")
     *
     * Se null: L'output è rimasto in formato JSON, nessun file generato
     * Se valorizzato: File disponibile per il download all'URL downloadUrl
     *
     * Estensioni possibili:
     * - .csv: CSV semicolon-separated (per Excel IT)
     * - .xlsx: Excel workbook (con formattazione)
     * - .docx: Word document (con gerarchia)
     */
    private String fileName;

    /**
     * 🔗 URL relativo per scaricare il file generato
     *
     * Presente solo se fileName è valorizzato
     * Formato: /api/reports/download/{fileName}
     *
     * Esempio: "/api/reports/download/550e8400-e29b-41d4-a716-446655440000.xlsx"
     *
     * Utilizzo:
     * {@code
     * GET /api/reports/download/550e8400-e29b-41d4-a716-446655440000.xlsx
     * }
     *
     * Il server restituirà il file con:
     * - Content-Type appropriato (application/vnd.ms-excel per XLSX)
     * - Content-Disposition: attachment (download forzato)
     * - Il file binario come body
     *
     * Se null: Non c'è file da scaricare (formato JSON)
     */
    private String downloadUrl;

    /**
     * 📊 Metadati di esecuzione e qualità del report
     *
     * Contiene informazioni utili per monitoraggio e debugging:
     * - Tempo di esecuzione in milliseconds
     * - Score di qualità dell'output (0.0 - 1.0)
     * - Score di qualità del contesto
     * - Token utilizzati
     * - Numero documenti KB usati
     * - Similarità media dei documenti
     * - Timestamp di generazione
     * - Flag di hallucination risk
     *
     * Usato per:
     * ✅ Monitoraggio performance
     * ✅ Debugging problemi
     * ✅ Analisi qualità
     * ✅ Tracciamento anomalie
     *
     * Sempre presente in risposta positiva
     */
    private ResponseMetadata metadata;
}
