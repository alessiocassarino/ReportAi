package com.claude.reportAi.dto;

import com.claude.reportAi.constant.ValidationIssue;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 🔍 DTO per il risultato della validazione dell'output AI
 *
 * OutputValidationResult contiene i risultati della validazione del testo
 * generato da Claude AI. Verifica che l'output sia di qualità accettabile
 * e non contenga allucinazioni o errori.
 *
 * Validazioni eseguite:
 * ✅ Lunghezza minima (almeno 100 caratteri)
 * ✅ Lunghezza massima (massimo 50000 caratteri)
 * ✅ Rilevamento allucinazioni (parole chiave sospette)
 * ✅ Struttura (deve contenere header, liste o paragrafi)
 * ✅ Formato (parentesi, virgolette bilanciate)
 *
 * Utilizzo:
 * Se qualityScore < 0.6, il report ha problemi di qualità
 * Se qualityScore < 0.4, il report è critico (alto rischio allucinazioni)
 *
 * @author ReportAI Team
 * @version 1.2.0
 * @since 2025-01-15
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutputValidationResult {

    /**
     * 📊 Score di qualità dell'output (0.0 - 1.0)
     *
     * Scala:
     * - 1.0: Qualità eccellente (output perfetto)
     * - 0.8-0.99: Qualità buona (output accettabile)
     * - 0.6-0.79: Qualità moderata (output usabile con cautela)
     * - 0.4-0.59: Qualità scarsa (probabile revisione necessaria)
     * - < 0.4: Qualità critica (alta probabilità di allucinazioni)
     *
     * Calcolato diminuendo da 1.0 in base agli errori trovati:
     * - Allucinazione rilevata: -0.30
     * - Output troppo corto: -0.15
     * - Output troppo lungo: -0.10
     * - Struttura povera: -0.10
     * - Formato non valido: -0.20
     */
    private Double qualityScore;

    /**
     * ⚠️ Lista di problemi rilevati durante la validazione
     *
     * Ogni elemento descrive un problema specifico:
     * - TOO_SHORT: Output inferiore a 100 caratteri
     * - TOO_LONG: Output superiore a 50000 caratteri
     * - POSSIBLE_HALLUCINATION: Rilevate parole chiave di allucinazione
     * - POOR_STRUCTURE: Manca struttura (header, liste, paragrafi)
     * - FORMAT_INVALID: Parentesi/virgolette non bilanciate
     * - EMPTY_OUTPUT: Output completamente vuoto
     * - ENCODING_ERROR: Problemi di encoding detectati
     *
     * Lista vuota = nessun problema rilevato
     */
    private List<ValidationIssue> issues;

    /**
     * 🗂️ Flag indicante se il contesto era disponibile
     *
     * - true: Documents trovati nel KB o ricerca web eseguita
     * - false: Nessun contesto esterno disponibile
     *
     * Usato per interpretare gli score:
     * - Con contesto: score < 0.6 è problematico
     * - Senza contesto: score < 0.4 è problematico (più tollerante)
     */
    private boolean contextFound;

    /**
     * 💬 Messaggio leggibile di validazione
     *
     * Traduce lo score in un messaggio user-friendly:
     * - "Output quality excellent" (>= 0.8)
     * - "Output quality acceptable" (0.6-0.79)
     * - "Output quality poor, potential hallucinations" (0.4-0.59)
     * - "Output quality critical, high hallucination risk" (< 0.4)
     */
    private String validationMessage;

    /**
     * Costruttore alternativo per creazione rapida
     *
     * @param qualityScore Score da 0.0 a 1.0
     * @param issues Lista di problemi rilevati
     * @param contextFound Se era disponibile contesto
     */
    public OutputValidationResult(Double qualityScore, List<ValidationIssue> issues, boolean contextFound) {
        this.qualityScore = qualityScore;
        this.issues = issues != null ? issues : new ArrayList<>();
        this.contextFound = contextFound;
        this.validationMessage = generateMessage();
    }

    /**
     * 🔄 Genera il messaggio di validazione basato sullo score
     * @return Messaggio user-friendly
     */
    private String generateMessage() {
        if (qualityScore >= 0.8) {
            return "Output quality excellent";
        } else if (qualityScore >= 0.6) {
            return "Output quality acceptable";
        } else if (qualityScore >= 0.4) {
            return "Output quality poor, potential hallucinations";
        } else {
            return "Output quality critical, high hallucination risk";
        }
    }

    /**
     * ✅ Verifica se l'output è accettabile
     * @return true se qualityScore >= 0.6
     */
    public boolean isAcceptable() {
        return qualityScore >= 0.6;
    }

    /**
     * ⚠️ Verifica se l'output è critico
     * @return true se qualityScore < 0.4
     */
    public boolean isCritical() {
        return qualityScore < 0.4;
    }
}
