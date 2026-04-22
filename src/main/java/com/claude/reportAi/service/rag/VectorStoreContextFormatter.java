package com.claude.reportAi.service.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Formatta una lista di Document recuperati dal vector store aggiungendo
 * etichette temporali (data caricamento + età relativa) e ordinando i chunk
 * dal più recente al più vecchio.
 *
 * Riutilizzabile da qualsiasi servizio RAG dell'applicazione.
 */
@Component
@Slf4j
public class VectorStoreContextFormatter {

    private static final DateTimeFormatter MONTH_YEAR_FMT = DateTimeFormatter
            .ofPattern("MMM yyyy", Locale.ITALIAN)
            .withZone(ZoneId.systemDefault());

    /**
     * Ordina i documenti per data di caricamento (recente prima) e produce
     * una stringa di testo con intestazione per chunk che include filename,
     * età relativa e data formattata.
     */
    public String format(List<Document> documents) {
        if (documents == null || documents.isEmpty()) return "";

        List<Document> sorted = documents.stream()
                .sorted(Comparator.comparing(
                        this::extractUploadedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())
                ))
                .toList();

        StringBuilder sb = new StringBuilder();
        for (Document doc : sorted) {
            String filename = getFilename(doc);
            Instant uploadedAt = extractUploadedAt(doc);
            String temporalLabel = buildTemporalLabel(uploadedAt);

            sb.append("=== ").append(filename).append(temporalLabel).append(" ===\n");
            sb.append(doc.getText()).append("\n\n");
        }

        return sb.toString();
    }

    private String buildTemporalLabel(Instant uploadedAt) {
        if (uploadedAt == null) return "";
        return " | Caricato: " + formatRelativeAge(uploadedAt)
                + " (" + MONTH_YEAR_FMT.format(uploadedAt) + ")";
    }

    private String formatRelativeAge(Instant uploadedAt) {
        long days = ChronoUnit.DAYS.between(uploadedAt, Instant.now());
        if (days < 7) return "questa settimana";
        if (days < 30) {
            long weeks = days / 7;
            return weeks == 1 ? "1 settimana fa" : weeks + " settimane fa";
        }
        long months = ChronoUnit.MONTHS.between(
                uploadedAt.atZone(ZoneId.systemDefault()).toLocalDate(),
                Instant.now().atZone(ZoneId.systemDefault()).toLocalDate()
        );
        if (months < 12) return months <= 1 ? "1 mese fa" : months + " mesi fa";
        long years = months / 12;
        long remainingMonths = months % 12;
        String yearPart = years == 1 ? "1 anno" : years + " anni";
        if (remainingMonths == 0) return yearPart + " fa";
        String monthPart = remainingMonths == 1 ? "1 mese" : remainingMonths + " mesi";
        return yearPart + " e " + monthPart + " fa";
    }

    private Instant extractUploadedAt(Document doc) {
        if (doc.getMetadata() == null) return null;
        Object raw = doc.getMetadata().get("uploadedAt");
        if (raw == null) return null;
        try {
            return Instant.parse(raw.toString());
        } catch (Exception e) {
            log.debug("Impossibile parsare uploadedAt '{}': {}", raw, e.getMessage());
            return null;
        }
    }

    private String getFilename(Document doc) {
        if (doc.getMetadata() == null) return "documento";
        Object filename = doc.getMetadata().get("filename");
        if (filename != null) return filename.toString();
        Object source = doc.getMetadata().get("source");
        if (source != null) return source.toString();
        return "documento";
    }
}
