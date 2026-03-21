package com.claude.reportAi.controller;

import com.claude.reportAi.dto.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final DateTimeFormatter dateFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(
            IllegalArgumentException ex,
            WebRequest request) {

        String traceId = generateTraceId();
        log.warn("Illegal argument error -> traceId={}, message={}", traceId, ex.getMessage());

        ErrorResponse response = ErrorResponse.builder()
                .errorCode("INVALID_ARGUMENT")
                .message(ex.getMessage())
                .errorType("CLIENT_ERROR")
                .status(400)
                .timestamp(LocalDateTime.now().format(dateFormatter))
                .path(request.getDescription(false).replace("uri=", ""))
                .traceId(traceId)
                .build();

        return new ResponseEntity<>(response, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(
            IllegalStateException ex,
            WebRequest request) {

        String traceId = generateTraceId();
        log.error("Illegal state error -> traceId={}, message={}", traceId, ex.getMessage(), ex);

        ErrorResponse response = ErrorResponse.builder()
                .errorCode("INVALID_STATE")
                .message(ex.getMessage())
                .errorType("SERVER_ERROR")
                .status(500)
                .timestamp(LocalDateTime.now().format(dateFormatter))
                .path(request.getDescription(false).replace("uri=", ""))
                .traceId(traceId)
                .details(ex.getCause() != null ? ex.getCause().getMessage() : null)
                .build();

        return new ResponseEntity<>(response, HttpStatus.INTERNAL_SERVER_ERROR);
    }



    @ExceptionHandler(IOException.class)
    public ResponseEntity<ErrorResponse> handleIOException(
            java.io.IOException ex,
            WebRequest request) {

        String traceId = generateTraceId();
        log.error("IO error -> traceId={}, message={}", traceId, ex.getMessage(), ex);

        ErrorResponse response = ErrorResponse.builder()
                .errorCode("IO_ERROR")
                .message("File operation failed")
                .errorType("IO_ERROR")
                .status(500)
                .timestamp(LocalDateTime.now().format(dateFormatter))
                .path(request.getDescription(false).replace("uri=", ""))
                .traceId(traceId)
                .details(ex.getMessage())
                .build();

        return new ResponseEntity<>(response, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(
            Exception ex,
            WebRequest request) {

        String traceId = generateTraceId();
        log.error("Unhandled exception -> traceId={}, type={}, message={}", 
                traceId, ex.getClass().getSimpleName(), ex.getMessage(), ex);

        String errorType = categorizeException(ex);
        HttpStatus httpStatus = getHttpStatusForErrorType(errorType);

        ErrorResponse response = ErrorResponse.builder()
                .errorCode("INTERNAL_ERROR")
                .message("An unexpected error occurred")
                .errorType(errorType)
                .status(httpStatus.value())
                .timestamp(LocalDateTime.now().format(dateFormatter))
                .path(request.getDescription(false).replace("uri=", ""))
                .traceId(traceId)
                .details(ex.getMessage())
                .build();

        return new ResponseEntity<>(response, httpStatus);
    }

    private String categorizeException(Exception ex) {
        if (ex.getMessage() != null) {
            String msg = ex.getMessage().toLowerCase();
            if (msg.contains("ai") || msg.contains("claude") || msg.contains("anthropic")) {
                return "AI_ERROR";
            }
            if (msg.contains("io") || msg.contains("file") || msg.contains("read")) {
                return "IO_ERROR";
            }
        }
        return "SERVER_ERROR";
    }

    private HttpStatus getHttpStatusForErrorType(String errorType) {
        return switch (errorType) {
            case "CLIENT_ERROR" -> HttpStatus.BAD_REQUEST;
            case "AI_ERROR" -> HttpStatus.SERVICE_UNAVAILABLE;
            case "IO_ERROR" -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    private String generateTraceId() {
        return UUID.randomUUID().toString();
    }
}
