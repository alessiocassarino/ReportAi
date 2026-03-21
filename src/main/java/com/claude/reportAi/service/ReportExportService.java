package com.claude.reportAi.service;

import com.claude.reportAi.dto.MarkdownTable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReportExportService {

    private static final String FORMAT_JSON = "JSON";
    private static final String FORMAT_CSV = "CSV";
    private static final String FORMAT_XLSX = "XLSX";
    private static final String FORMAT_DOCX = "DOCX";
    private static final String FORMAT_WORD = "WORD";

    private static final String DEFAULT_SHEET_NAME = "Report";
    private static final String FALLBACK_CONTENT_HEADER = "Contenuto Report";
    private static final String NO_CONTENT_MESSAGE = "Nessun contenuto disponibile";

    private final MarkdownTableExtractor tableExtractor;
    private final XlsxProExportService xlsxProExportService;
    private final DocxProExportService docxProExportService;
    private final SmartXlsxFallbackService smartXlsxFallbackService;

    @Value("${app.reports.root:./data/reports}")
    private String reportsRoot;

    public String export(String content, String format) {
        try {
            Path exportDir = ensureExportDirectory();
            String normalizedFormat = normalizeFormat(format);

            log.info("Avvio export report -> format='{}'", normalizedFormat);

            // Extract table from content
            MarkdownTable table = tableExtractor.extractTable(content);

            return switch (normalizedFormat) {
                case FORMAT_CSV -> table.isValid()
                        ? exportCsvFromTable(table, exportDir)
                        : exportPlainTextCsv(content, exportDir);
                case FORMAT_XLSX -> table.isValid()
                        ? xlsxProExportService.exportXlsxPro(table, exportDir)
                        : smartXlsxFallbackService.exportFallbackXlsx(content, exportDir);
                case FORMAT_DOCX -> docxProExportService.exportDocxPro(content, exportDir);
                default -> throw new IllegalArgumentException("Formato non supportato: " + format);
            };

        } catch (Exception e) {
            log.error("Errore durante export report: {}", e.getMessage(), e);
            throw new IllegalStateException("Errore durante export report", e);
        }
    }

    public Resource loadAsResource(String fileName) {
        try {
            Path filePath = Path.of(reportsRoot).resolve(fileName).normalize();

            if (!Files.exists(filePath)) {
                throw new IllegalArgumentException("File non trovato: " + fileName);
            }

            return new PathResource(filePath);
        } catch (Exception e) {
            throw new IllegalStateException("Errore durante il caricamento del file", e);
        }
    }

    public String resolveContentType(String fileName) {
        String lower = fileName.toLowerCase();

        if (lower.endsWith(".csv")) {
            return "text/csv";
        }

        if (lower.endsWith(".xlsx")) {
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        }

        if (lower.endsWith(".docx")) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }

        return "application/octet-stream";
    }

    private String exportCsvFromTable(MarkdownTable table, Path exportDir) throws IOException {
        String fileName = UUID.randomUUID() + ".csv";
        Path path = exportDir.resolve(fileName);

        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            // BOM for Excel compatibility
            writer.write('\ufeff');

            // Headers
            List<String> escapedHeaders = table.getHeaders().stream()
                    .map(this::escapeCsv)
                    .toList();
            writer.write(String.join(";", escapedHeaders));
            writer.newLine();

            // Data rows
            for (List<String> row : table.getRows()) {
                List<String> escapedRow = row.stream()
                        .map(this::escapeCsv)
                        .toList();
                writer.write(String.join(";", escapedRow));
                writer.newLine();
            }
        }

        log.info("Export CSV completato -> fileName='{}', rows={}", fileName, table.getRows().size());
        return fileName;
    }

    private String exportPlainTextCsv(String content, Path exportDir) throws IOException {
        String fileName = UUID.randomUUID() + ".csv";
        Path path = exportDir.resolve(fileName);

        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            // BOM
            writer.write('\ufeff');

            // Header
            writer.write(escapeCsv(FALLBACK_CONTENT_HEADER));
            writer.newLine();

            // Content lines
            List<String> lines = splitLines(content);
            for (String line : lines) {
                writer.write(escapeCsv(line));
                writer.newLine();
            }
        }

        log.info("Export CSV testuale completato -> fileName='{}', rows={}", fileName, splitLines(content).size());
        return fileName;
    }

    private Path ensureExportDirectory() throws IOException {
        Path exportDir = Path.of(reportsRoot);
        Files.createDirectories(exportDir);
        return exportDir;
    }

    private List<String> splitLines(String content) {
        if (content == null || content.isBlank()) {
            return List.of(NO_CONTENT_MESSAGE);
        }

        return content.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .toList();
    }

    private String escapeCsv(String value) {
        String safe = nullSafe(value)
                .replace("\"", "\"\"")
                .replace("\n", " ")
                .replace("\r", " ");
        return "\"" + safe + "\"";
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private String normalizeFormat(String format) {
        if (format == null || format.isBlank()) {
            return FORMAT_JSON;
        }

        String normalized = format.trim().toUpperCase();
        if (FORMAT_WORD.equals(normalized)) {
            return FORMAT_DOCX;
        }
        return normalized;
    }
}
