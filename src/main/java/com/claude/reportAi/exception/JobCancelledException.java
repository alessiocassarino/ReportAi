package com.claude.reportAi.exception;

import java.util.UUID;

/**
 * Lanciata dai processor quando rilevano che il job è stato annullato dall'utente.
 * È unchecked così può attraversare metodi che non dichiarano checked exceptions.
 */
public class JobCancelledException extends RuntimeException {

    public JobCancelledException(UUID jobId) {
        super("Job annullato dall'utente: " + jobId);
    }
}
