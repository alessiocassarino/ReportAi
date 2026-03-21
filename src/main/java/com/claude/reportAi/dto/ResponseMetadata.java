package com.claude.reportAi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 📊 DTO per i metadati di esecuzione della generazione report
 *
 * ResponseMetadata contiene informazioni dettagliate sull'esecuzione
 * della generazione del report. Usato per monitoraggio, debugging e analisi.
 *
 * Dati inclusi:
 * ✅ Tempo di esecuzione totale
 * ✅ Token consumati da Claude
 * ✅ Score di qualità dell'output
 * ✅ Score di qualità del contesto
 * ✅ Numero documenti dal KB usati
 * ✅ Similarità media documenti
 * ✅ Modello generazione usato
 * ✅ Timestamp generazione
 * ✅ Flag rischio allucinazione
 * ✅ Messaggio validazione
 *
 * Questo DTO è sempre incluso nella risposta e non può essere null.
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
public class ResponseMetadata {

    /**
     * ⏱️ Tempo totale di esecuzione in millisecondi
     *
     * Misura il tempo dall'inizio della richiesta al completamento.
     * Include:
     * - Ricerca nel knowledge base: ~200-500ms
     * - Generazione con Claude: ~1000-3000ms
     * - Export file: ~100-500ms
     *
     * Valori tipici:
     * - Rapido: < 2000ms (KB trovato, nessun web search)
     * - Normale: 2000-4000ms (KB + generazione)
     * - Lento: > 4000ms (web search + generazione)
     *
     * Usato per monitorare la performance dell'applicazione
     */
    private Long executionTimeMs;

    /**
     * 🎯 Token consumati da Claude AI
     *
     * Numero di token utilizzati dal modello per generare il report.
     * 1 token ≈ 4 caratteri in media.
     *
     * Valori tipici:
     * - Prompts piccoli: 100-300 token
     * - Reports medi: 300-800 token
     * - Reports grandi: 800-1500 token
     *
     * Limitazione:
     * - max_tokens impostato a 1536 nel config
     * - Se output raggiunge questo limite, report potrebbe essere incompleto
     *
     * Usato per:
     * ✅ Calcolo costi API Anthropic
     * ✅ Monitoraggio utilizzo risorse
     */
    private Integer tokensUsed;

    /**
     * 🔍 Score di qualità dell'output generato (0.0 - 1.0)
     *
     * Calcolato da OutputValidationService.
     * Rappresenta quanto l'output è di qualità:
     *
     * Scala:
     * - >= 0.8: Excellent (usare direttamente)
     * - 0.6-0.79: Good (accettabile, minor review)
     * - 0.4-0.59: Poor (revisione consigliata)
     * - < 0.4: Critical (alto rischio allucinazioni)
     *
     * Se score basso:
     * - Considerare di rigenerare il report
     * - Aumentare il contesto nel KB
     * - Abilitare web search
     */
    private Double outputQualityScore;

    /**
     * 🗂️ Score di qualità del contesto disponibile (0.0 - 1.0)
     *
     * Calcolato da ContextQualityService.
     * Rappresenta quanto il contesto è rilevante:
     *
     * Fattori considerati:
     * - Similarità media documenti
     * - Numero di documenti trovati
     * - Matching di metadati
     * - Completeness del contesto
     * - Freschezza documenti
     *
     * Scala:
     * - >= 0.7: High (contesto ottimale)
     * - 0.5-0.69: Medium (contesto accettabile)
     * - 0.3-0.49: Low (contesto limitato)
     * - < 0.3: Critical (contesto insufficiente)
     *
     * Se score basso, generare più documenti nel KB
     */
    private Double contextQualityScore;

    /**
     * 📚 Numero di documenti dal knowledge base usati
     *
     * Conta i documenti dal vector store incorporati nel prompt di Claude.
     * Massimo 5 documenti per evitare token overflow.
     *
     * Valori possibili:
     * - 0: Nessun documento trovato (possibile web search)
     * - 1-3: Contesto limitato
     * - 4-5: Contesto completo
     *
     * Se 0 e webSearchUsed è false:
     * - Report generato senza contesto esterno (rischio allucinazioni)
     * - Considerare di caricare più documenti nel KB
     */
    private Integer knowledgeBaseDocumentsUsed;

    /**
     * 📊 Similarità media dei documenti recuperati (0.0 - 1.0)
     *
     * Calcola la media del punteggio di similarità coseno tra
     * la query e i documenti trovati.
     *
     * Scala:
     * - >= 0.7: Documents altamente rilevanti
     * - 0.5-0.69: Documents moderatamente rilevanti
     * - 0.3-0.49: Documents debolmente rilevanti
     * - < 0.3: Documents poco rilevanti
     *
     * Se similarity bassa:
     * - I documenti potrebbero non essere i migliori match
     * - Considerare di usare web search per integrare
     * - Migliorare il prompt della query
     */
    private Double similarityAverage;

    /**
     * 🤖 Modello AI usato per la generazione
     *
     * Identificazione univoca del modello Claude:
     * - "claude-sonnet-4-6" (configurazione di default)
     * - "claude-opus" (se configurato)
     * - "claude-haiku" (se configurato)
     *
     * Usato per:
     * ✅ Tracking quale modello ha generato il report
     * ✅ Debugging problemi specifici del modello
     * ✅ Analisi costi API per modello
     */
    private String generationModel;

    /**
     * 🕐 Timestamp della generazione in formato ISO 8601
     *
     * Indica il momento esatto in cui il report è stato generato.
     * Formato: "2025-01-15T14:30:00"
     *
     * Usato per:
     * ✅ Auditoria (traccia quando è stato creato)
     * ✅ Ordinamento cronologico
     * ✅ Analisi trend temporali
     * ✅ Debugging (quale versione è stata usata)
     */
    private LocalDateTime generatedAt;

    /**
     * ⚠️ Flag indicante se è stato rilevato rischio di allucinazione
     *
     * - true: OutputValidationService ha rilevato possibili allucinazioni
     *   (score < 0.4 o problemi strutturali)
     * - false: Output sembra affidabile
     *
     * Se true:
     * - Considerare di non usare il report direttamente
     * - Richiedere review manuale
     * - Rigenerare con contesto migliore
     * - Disabilitare web search se causava problemi
     */
    private Boolean halluccinationRiskDetected;

    /**
     * 💬 Messaggio leggibile dei risultati della validazione
     *
     * Traduce gli score in messaggio user-friendly:
     * - "Output quality excellent"
     * - "Output quality acceptable"
     * - "Output quality poor, potential hallucinations"
     * - "Output quality critical, high hallucination risk"
     *
     * Utile per interfacce utente che vogliono mostrare feedback
     */
    private String validationMessage;
}
