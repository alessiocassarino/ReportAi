package com.claude.reportAi.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 📊 DTO per le metriche di qualità del contesto recuperato
 *
 * ContextQualityMetrics contiene i risultati della valutazione della qualità
 * del contesto assemblato da knowledge base, ricerca web e documenti temporanei.
 *
 * Questo score è cruciale perché influenza direttamente la qualità dell'output
 * generato da Claude. Se il contesto è scarso, l'output sarà scarso.
 *
 * Metriche incluse:
 * ✅ Similarità media documenti (0.0-1.0)
 * ✅ Numero totale documenti
 * ✅ Se metadati sono stati matchati
 * ✅ Score di completezza (0.0-1.0)
 * ✅ Score di freschezza (0.0-1.0)
 * ✅ Score di rilevanza (0.0-1.0)
 * ✅ Summary testuale
 *
 * Scoring complessivo:
 * overallScore = (avgSimilarity * 0.4) + (completeness * 0.3) + (relevance * 0.3)
 *
 * @author ReportAI Team
 * @version 1.2.0
 * @since 2025-01-15
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContextQualityMetrics {

    /**
     * 📏 Similarità media dei documenti recuperati (0.0-1.0)
     *
     * Calcola la media del punteggio di similarità coseno tra
     * la query utente e i documenti trovati nel knowledge base.
     *
     * Scala:
     * - >= 0.7: Documents altamente rilevanti (match eccellente)
     * - 0.5-0.69: Documents moderatamente rilevanti (match buono)
     * - 0.3-0.49: Documents debolmente rilevanti (match scarso)
     * - < 0.3: Documents poco rilevanti (match pessimo)
     *
     * Se basso (< 0.4):
     * - I documenti non sono ideali per il prompt
     * - Considerare di aggiungere più documenti nel KB
     * - Considerare di abilitare web search
     *
     * Peso nel scoring: 40%
     */
    private Double averageSimilarity;

    /**
     * 📚 Numero totale di documenti recuperati
     *
     * Conta tutti i documenti incorporati nel contesto:
     * - Da knowledge base
     * - Da upload temporanei
     * - Da reference data
     * - Da ricerca web
     *
     * Valori tipici:
     * - 0: Nessun contesto (rischio allucinazioni)
     * - 1-3: Contesto limitato (scarso)
     * - 4-5: Contesto completo (buono)
     * - 6+: Contesto ricco (eccellente)
     *
     * Limitazione: Max 5 dal KB per evitare token overflow
     */
    private Integer totalDocuments;

    /**
     * 🏷️ Flag se i metadati sono stati matched
     *
     * Indica se il matching di metadati ha trovato corrispondenze:
     * - true: Documenti con metadati rilevanti trovati
     *   (es: stesso cliente, settore, tipo documento)
     * - false: Documenti trovati solo per similarità testo
     *
     * Se true, il match è probabilmente migliore
     * Metadati considerati:
     * - documentType
     * - clientName
     * - sector
     * - projectName
     * - contractType
     */
    private Boolean metadataMatched;

    /**
     * 📋 Score di completezza del contesto (0.0-1.0)
     *
     * Misura quanto il contesto copre i termini importanti della query.
     *
     * Algoritmo semplificato:
     * 1. Estrae termini chiave dal prompt (lunghezza > 3 caratteri)
     * 2. Conta quanti termini sono presenti nel contesto
     * 3. Calcola percentuale: matched_terms / total_terms
     *
     * Scala:
     * - 1.0: Tutti i termini presenti nel contesto (completezza perfetta)
     * - 0.7-0.99: Maggior parte dei termini presenti (completezza buona)
     * - 0.4-0.69: Alcuni termini presenti (completezza moderata)
     * - < 0.4: Pochi termini presenti (completezza scarsa)
     *
     * Se basso:
     * - Il contesto non copre adeguatamente il topic
     * - Aggiungere documenti più specifici nel KB
     *
     * Peso nel scoring: 30%
     */
    private Double completenessScore;

    /**
     * 🕐 Score di freschezza dei documenti (0.0-1.0)
     *
     * Indica se i documenti sono recenti o obsoleti.
     *
     * Algoritmo:
     * 1. Verifica data di creazione documenti
     * 2. Se creati recentemente: score alto
     * 3. Se vecchi di mesi: score basso
     *
     * Scala:
     * - 1.0: Documenti creati negli ultimi 7 giorni
     * - 0.7-0.99: Documenti di questo mese
     * - 0.4-0.69: Documenti ultimi 3 mesi
     * - < 0.4: Documenti molto vecchi (> 3 mesi)
     *
     * Note:
     * - Implementazione corrente ritorna 1.0 (sempre fresco)
     * - TODO: Integrare con data documento da metadati
     *
     * Peso nel scoring: Non direttamente incluso (reserve per futuro)
     */
    private Double freshnessScore;

    /**
     * 🎯 Score di rilevanza del contesto (0.0-1.0)
     *
     * Misura la rilevanza complessiva del contesto in base a:
     * - Numero di documenti (max 1.0 a 10 doc)
     * - Metadati matchati (+0.1 bonus)
     *
     * Formula:
     * relevance = min(docCount / 10.0, 1.0) + (hasMetadata ? 0.1 : 0)
     *
     * Scala:
     * - >= 0.9: Contesto altamente rilevante
     * - 0.7-0.89: Contesto rilevante
     * - 0.5-0.69: Contesto moderatamente rilevante
     * - < 0.5: Contesto poco rilevante
     *
     * Peso nel scoring: 30%
     */
    private Double relevanceScore;

    /**
     * 💬 Riassunto testuale della qualità (per UI)
     *
     * Traduce i score in messaggio leggibile:
     * - "Excellent context quality - All indicators strong"
     * - "Good context quality - Suitable for generation"
     * - "Moderate context quality - Some gaps expected"
     * - "Poor context quality - High hallucination risk"
     *
     * Utile per interfacce che vogliono mostrare feedback all'utente
     * senza scendere nei dettagli tecnici
     */
    private String qualitySummary;

    /**
     * 📊 Calcola lo score complessivo di qualità contesto
     *
     * Formula ponderata:
     * overallScore = (avgSimilarity * 0.4) + (completeness * 0.3) + (relevance * 0.3)
     *
     * Risultato: 0.0 - 1.0
     * - Nessun valore disponibile: 0.0
     * - Tutti i valori null: 0.0
     *
     * @return Score complessivo 0.0-1.0
     */
    public Double getOverallScore() {
        if (averageSimilarity == null || completenessScore == null || relevanceScore == null) {
            return 0.0;
        }
        return (averageSimilarity * 0.4) + (completenessScore * 0.3) + (relevanceScore * 0.3);
    }

    /**
     * ✅ Verifica se la qualità contesto è alta
     *
     * @return true se overallScore >= 0.7 (buono/eccellente)
     */
    public boolean isHighQuality() {
        return getOverallScore() >= 0.7;
    }

    /**
     * ⚠️ Verifica se la qualità contesto è bassa
     *
     * @return true se overallScore < 0.4 (scarso/pessimo)
     */
    public boolean isLowQuality() {
        return getOverallScore() < 0.4;
    }
}
