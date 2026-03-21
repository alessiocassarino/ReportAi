package com.claude.reportAi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 📋 DTO per la richiesta di generazione report
 *
 * ReportRequest rappresenta la richiesta HTTP che l'utente invia all'endpoint
 * POST /api/reports/generate per generare un nuovo report.
 *
 * Campi disponibili:
 * - prompt: Istruzioni per la generazione del report (obbligatorio)
 * - format: Formato di esportazione (JSON, CSV, XLSX, DOCX)
 * - allowWebSearch: Se abilitare la ricerca web come fallback
 * - systemPromptId: ID del system prompt personalizzato (opzionale)
 * - selectedTemplateId: ID del template da usare (opzionale)
 * - outputFileName: Nome file output personalizzato (opzionale)
 *
 * Esempio di utilizzo:
 * {@code
 * POST /api/reports/generate
 * Content-Type: application/json
 *
 * {
 *   "prompt": "Analizza le vendite di Q4 2024",
 *   "format": "XLSX",
 *   "allowWebSearch": false
 * }
 * }
 *
 * Validazioni applicate:
 * ✅ prompt non può essere nullo o vuoto
 * ✅ prompt massimo 2000 caratteri
 * ✅ format deve essere uno tra: JSON, CSV, XLSX, DOCX
 * ✅ allowWebSearch è un booleano
 *
 * @author ReportAI Team
 * @version 1.2.0
 * @since 2025-01-15
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReportRequest {

    /**
     * 💬 Prompt per la generazione del report
     *
     * Campo obbligatorio che contiene le istruzioni specifiche per Claude AI.
     * Deve descrivere chiaramente cosa l'utente vuole nel report.
     *
     * Esempi validi:
     * - "Analizza i dati di vendita del Q4 2024"
     * - "Crea una tabella con i clienti top 10 e loro importi"
     * - "Genera un SWOT analysis della concorrenza"
     *
     * Massimo 2000 caratteri per evitare timeout
     */
    private String prompt;

    /**
     * 📊 Formato di esportazione del report
     *
     * Specifica il formato del file di output:
     * - JSON: Output grezzo senza file (default)
     * - CSV: Comma-separated values (semicolon separator per locale IT)
     * - XLSX: Microsoft Excel (con formattazione professionale)
     * - DOCX: Microsoft Word (con gerarchia heading)
     *
     * Se non specificato, di default è JSON
     */
    private String format;

    /**
     * 🔍 Abilita ricerca web come fallback
     *
     * Se true e il knowledge base non contiene risultati utili,
     * l'applicazione eseguirà una ricerca web per integrare il contesto.
     *
     * Default: true (ricerca web abilitata)
     * Richiede: app.web-search.enabled=true e API key configurata
     */
    private boolean allowWebSearch = true;

    /**
     * 🎨 ID del system prompt personalizzato
     *
     * Opzionale. Se specificato, usa un system prompt personalizzato
     * salvato nel database invece del default.
     *
     * Permette agli utenti di personalizzare il comportamento di Claude
     * per casi d'uso specifici (es: tone, lunghezza, stile, lingua).
     *
     * Default: null (usa system prompt di default)
     */
    private Integer systemPromptId;

    /**
     * 🎯 ID del template da usare
     *
     * Opzionale. Se specificato, il report viene generato secondo
     * il template salvato con questo ID.
     *
     * I template permettono di standardizzare il formato dei report
     * per team o dipartimenti specifici.
     *
     * Default: null (nessun template)
     */
    private Long selectedTemplateId;

    /**
     * 📁 Nome file output personalizzato
     *
     * Opzionale. Se specificato, il file esportato avrà questo nome
     * invece del UUID autogenerato.
     *
     * Utile quando l'utente vuole un nome descrittivo specifico.
     *
     * Default: null (nome autogenerato con UUID)
     */
    private String outputFileName;
}
