package com.claude.reportAi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 🚨 DTO per risposta standardizzata di errore
 *
 * ErrorResponse rappresenta una risposta di errore strutturata che il server
 * restituisce quando qualcosa va male durante l'elaborazione della richiesta.
 *
 * Struttura di errore standardizzata:
 * ✅ errorCode: Codice errore specifico (es: INVALID_ARGUMENT)
 * ✅ message: Messaggio errore leggibile (es: "Prompt è richiesto")
 * ✅ errorType: Categoria errore (CLIENT/SERVER/AI/IO)
 * ✅ status: HTTP status code (400, 500, 503, etc.)
 * ✅ timestamp: Quando è avvenuto l'errore (ISO 8601)
 * ✅ traceId: ID univoco per tracciamento (UUID)
 * ✅ path: Path dell'endpoint che ha causato errore
 * ✅ details: Dettagli aggiuntivi (messaggio causa radice)
 *
 * Vantaggi:
 * ✅ Client conosce esattamente cosa è andato male
 * ✅ Logging/debugging centralizzato via traceId
 * ✅ Categoria errore aiuta nel retry logic
 * ✅ Coerente e prevedibile per client
 *
 * Esempio errore validazione:
 * {@code
 * {
 *   "errorCode": "INVALID_ARGUMENT",
 *   "message": "Prompt è richiesto",
 *   "errorType": "CLIENT_ERROR",
 *   "status": 400,
 *   "timestamp": "2025-01-15T14:30:00",
 *   "path": "/api/reports/generate",
 *   "traceId": "550e8400-e29b-41d4-a716-446655440000",
 *   "details": "Field 'prompt': must not be empty"
 * }
 * }
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
public class ErrorResponse {

    /**
     * 🔴 Codice errore specifico e univoco
     *
     * Esempi:
     * - "INVALID_ARGUMENT": Parametri richiesta non validi
     * - "INVALID_STATE": Applicazione in stato incoerente
     * - "PAYLOAD_TOO_LARGE": File/request supera limite
     * - "VALIDATION_ERROR": Validazione fallita
     * - "IO_ERROR": Errore lettura/scrittura file
     * - "AI_SERVICE_ERROR": Errore Claude API
     * - "INTERNAL_ERROR": Errore interno server
     *
     * Usato dal client per:
     * ✅ Retry logic (retry per INTERNAL_ERROR, non per INVALID_ARGUMENT)
     * ✅ Traduzione messaggio (es: se conosce il codice, sa quale azione prendere)
     * ✅ Logging/monitoraggio
     *
     * Non dovrebbe contenere spazi, usare SNAKE_CASE
     */
    private String errorCode;

    /**
     * 📝 Messaggio errore leggibile per l'utente
     *
     * Deve essere:
     * ✅ Chiaro e specifico (non generico)
     * ✅ Comprensibile a non-developer
     * ✅ Possibilmente suggerendo una soluzione
     *
     * Buono: "Prompt non può essere vuoto. Fornire istruzioni valide."
     * Cattivo: "NullPointerException at line 42"
     *
     * Lunghezza: preferibilmente < 200 caratteri
     */
    private String message;

    /**
     * 🏷️ Categoria dell'errore per classificazione
     *
     * Possibili valori:
     * - "CLIENT_ERROR": Errore causato dal client (es: richiesta invalida)
     *   HTTP 4xx (400, 413, etc.)
     * - "SERVER_ERROR": Errore interno server (es: database unavailable)
     *   HTTP 5xx (500, 503, etc.)
     * - "AI_ERROR": Errore del servizio AI (es: Claude API timeout)
     *   HTTP 503
     * - "IO_ERROR": Errore operazione file (es: disco pieno)
     *   HTTP 500
     *
     * Usato dal client per decidere se fare retry:
     * - CLIENT_ERROR: Non fare retry (errore dell'utente)
     * - SERVER_ERROR: Fare retry con backoff
     * - AI_ERROR: Fare retry (servizio temporaneamente unavailable)
     * - IO_ERROR: Fare retry
     */
    private String errorType;

    /**
     * 🔢 HTTP status code della risposta errore
     *
     * Valori standard HTTP:
     * - 400: Bad Request (INVALID_ARGUMENT, VALIDATION_ERROR)
     * - 413: Payload Too Large
     * - 500: Internal Server Error (SERVER_ERROR, IO_ERROR)
     * - 503: Service Unavailable (AI_ERROR)
     *
     * Deve essere coerente con HTTP spec
     */
    private Integer status;

    /**
     * 🕐 Timestamp di quando è avvenuto l'errore
     *
     * Formato: ISO 8601 (es: "2025-01-15T14:30:45.123")
     * Timezone: UTC (non locale)
     * Utile per correlazione negli audit log
     */
    private String timestamp;

    /**
     * 📍 Endpoint che ha causato l'errore
     *
     * Formato completo:
     * "/api/reports/generate" (POST)
     * "/api/documents/upload" (POST)
     * "/api/reports/download/{fileName}" (GET)
     *
     * Utile per debug e logging
     */
    private String path;

    /**
     * 🆔 ID univoco di tracciamento errore
     *
     * Formato: UUID (es: "550e8400-e29b-41d4-a716-446655440000")
     * Generato automaticamente dal GlobalExceptionHandler
     *
     * Scopo:
     * ✅ Permette al client di riferirsi a questo specifico errore
     * ✅ DevOps può cercare questo ID negli aggregatori di log
     * ✅ Facilita debugging quando cliente segnala problema
     *
     * Utilizzo: "Errore occurred, trace ID: 550e8400-..."
     */
    private String traceId;

    /**
     * 🔍 Dettagli aggiuntivi specifici dell'errore
     *
     * Può contenere:
     * - Stack trace parziale (non completo per sicurezza)
     * - Messaggio causa radice eccezione
     * - Dettagli validazione per campo
     * - Info aggiuntive context-specific
     *
     * Esempio: "Field 'prompt' failed validation: must not be empty"
     * Esempio: "Database connection timeout after 30 seconds"
     *
     * Usato principalmente per debug, può essere null in produzione
     */
    private String details;

    /**
     * 📦 Informazioni aggiuntive dipendenti dal tipo errore
     *
     * Oggetto dinamico che può contenere:
     * - Per validazione: lista di campi falliti
     * - Per timeout: tempo di attesa
     * - Per file: size limitato vs size inviato
     *
     * Usato dai client avanzati per azioni specifiche
     * Possibile null se non applicabile
     */
    private Object additionalInfo;

    /**
     * ✅ Factory method per errore client-side
     *
     * @param errorCode Codice errore (es: "INVALID_ARGUMENT")
     * @param message Messaggio leggibile
     * @return ErrorResponse con status 400 (Bad Request)
     */
    public static ErrorResponse clientError(String errorCode, String message) {
        return ErrorResponse.builder()
                .errorCode(errorCode)
                .message(message)
                .errorType("CLIENT_ERROR")
                .status(400)
                .build();
    }

    /**
     * 🔴 Factory method per errore server-side
     *
     * @param errorCode Codice errore (es: "INTERNAL_ERROR")
     * @param message Messaggio leggibile
     * @return ErrorResponse con status 500 (Internal Server Error)
     */
    public static ErrorResponse serverError(String errorCode, String message) {
        return ErrorResponse.builder()
                .errorCode(errorCode)
                .message(message)
                .errorType("SERVER_ERROR")
                .status(500)
                .build();
    }

    /**
     * 🤖 Factory method per errore servizio AI
     *
     * @param errorCode Codice errore (es: "AI_SERVICE_ERROR")
     * @param message Messaggio leggibile
     * @return ErrorResponse con status 503 (Service Unavailable)
     */
    public static ErrorResponse aiError(String errorCode, String message) {
        return ErrorResponse.builder()
                .errorCode(errorCode)
                .message(message)
                .errorType("AI_ERROR")
                .status(503)
                .build();
    }

    /**
     * 💾 Factory method per errore IO
     *
     * @param errorCode Codice errore (es: "IO_ERROR")
     * @param message Messaggio leggibile
     * @return ErrorResponse con status 500 (Internal Server Error)
     */
    public static ErrorResponse ioError(String errorCode, String message) {
        return ErrorResponse.builder()
                .errorCode(errorCode)
                .message(message)
                .errorType("IO_ERROR")
                .status(500)
                .build();
    }
}
