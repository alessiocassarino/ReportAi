package com.claude.reportAi.service;

import com.claude.reportAi.dto.ReportTableResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
public class ReportExportService {

    private static final String FORMAT_JSON = "JSON";
    private static final String FORMAT_CSV = "CSV";
    private static final String FORMAT_XLSX = "XLSX";
    private static final String FORMAT_DOCX = "DOCX";

    private static final String DEFAULT_SHEET_NAME = "Report";
    private static final String FALLBACK_CONTENT_HEADER = "Contenuto Report";
    private static final String NO_CONTENT_MESSAGE = "Nessun contenuto disponibile";

    @Value("${app.reports.root:./data/reports}")
    private String reportsRoot;

    public String export(String content, String format) {
        try {
            Path exportDir = ensureExportDirectory();
            String normalizedFormat = normalizeFormat(format);

            log.info("Avvio export report -> format='{}'", normalizedFormat);

            ReportTableResponse table = tryExtractTable(content);

            return switch (normalizedFormat) {
                case FORMAT_CSV -> hasTableData(table)
                        ? exportCsv(table, exportDir)
                        : exportPlainTextCsv(content, exportDir);
                case FORMAT_XLSX -> hasTableData(table)
                        ? exportXlsx(table, exportDir)
                        : exportPlainTextXlsx(content, exportDir);
                case FORMAT_DOCX -> exportDocx(content, exportDir);
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

    public String exportCsv(ReportTableResponse table) {
        try {
            return exportCsv(table, ensureExportDirectory());
        } catch (IOException e) {
            throw new IllegalStateException("Errore export CSV", e);
        }
    }

    public String exportXlsx(ReportTableResponse table) {
        try {
            return exportXlsx(table, ensureExportDirectory());
        } catch (IOException e) {
            throw new IllegalStateException("Errore export XLSX", e);
        }
    }

    public String exportDocx(String content) {
        try {
            return exportDocx(content, ensureExportDirectory());
        } catch (IOException e) {
            throw new IllegalStateException("Errore export DOCX", e);
        }
    }

    private Path ensureExportDirectory() throws IOException {
        Path exportDir = Path.of(reportsRoot);
        Files.createDirectories(exportDir);
        return exportDir;
    }

    private String exportCsv(ReportTableResponse table, Path exportDir) throws IOException {
        String fileName = UUID.randomUUID() + ".csv";
        Path path = exportDir.resolve(fileName);

        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            if (table.getHeaders() != null && !table.getHeaders().isEmpty()) {
                writer.write(String.join(";", table.getHeaders().stream().map(this::escapeCsv).toList()));
                writer.newLine();
            }

            if (table.getRows() != null) {
                for (List<String> row : table.getRows()) {
                    List<String> escaped = row.stream()
                            .map(this::escapeCsv)
                            .toList();
                    writer.write(String.join(";", escaped));
                    writer.newLine();
                }
            }
        }

        log.info("Export CSV completato -> fileName='{}'", fileName);
        return fileName;
    }

    private String exportXlsx(ReportTableResponse table, Path exportDir) throws IOException {
        String fileName = UUID.randomUUID() + ".xlsx";
        Path path = exportDir.resolve(fileName);

        try (Workbook workbook = new XSSFWorkbook();
             OutputStream os = Files.newOutputStream(path)) {

            Sheet sheet = workbook.createSheet(DEFAULT_SHEET_NAME);
            int rowIndex = 0;

            if (table.getHeaders() != null && !table.getHeaders().isEmpty()) {
                Row headerRow = sheet.createRow(rowIndex++);
                for (int i = 0; i < table.getHeaders().size(); i++) {
                    headerRow.createCell(i).setCellValue(nullSafe(table.getHeaders().get(i)));
                }
            }

            if (table.getRows() != null) {
                for (List<String> rowData : table.getRows()) {
                    Row row = sheet.createRow(rowIndex++);
                    for (int i = 0; i < rowData.size(); i++) {
                        row.createCell(i).setCellValue(nullSafe(rowData.get(i)));
                    }
                }
            }

            int columnCount = table.getHeaders() != null && !table.getHeaders().isEmpty()
                    ? table.getHeaders().size()
                    : inferMaxColumns(table);

            for (int i = 0; i < columnCount; i++) {
                sheet.autoSizeColumn(i);
            }

            workbook.write(os);
        }

        log.info("Export XLSX completato -> fileName='{}'", fileName);
        return fileName;
    }

    private String exportDocx(String content, Path exportDir) throws IOException {
        String fileName = UUID.randomUUID() + ".docx";
        Path path = exportDir.resolve(fileName);

        try (XWPFDocument document = new XWPFDocument();
             OutputStream os = Files.newOutputStream(path)) {

            XWPFParagraph title = document.createParagraph();
            title.createRun().setText("Report");

            for (String paragraphText : splitParagraphs(content)) {
                XWPFParagraph paragraph = document.createParagraph();
                paragraph.createRun().setText(paragraphText);
            }

            document.write(os);
        }

        log.info("Export DOCX completato -> fileName='{}'", fileName);
        return fileName;
    }

    private String exportPlainTextCsv(String content, Path exportDir) throws IOException {
        ReportTableResponse fallback = new ReportTableResponse();
        fallback.setHeaders(List.of(FALLBACK_CONTENT_HEADER));
        fallback.setRows(splitLines(content).stream()
                .map(line -> List.of(line))
                .toList());

        log.info("Nessuna tabella rilevata. Export CSV testuale di fallback.");
        return exportCsv(fallback, exportDir);
    }

    private String exportPlainTextXlsx(String content, Path exportDir) throws IOException {
        String fileName = UUID.randomUUID() + ".xlsx";
        Path path = exportDir.resolve(fileName);

        try (Workbook workbook = new XSSFWorkbook();
             OutputStream os = Files.newOutputStream(path)) {

            Sheet sheet = workbook.createSheet(DEFAULT_SHEET_NAME);

            Row headerRow = sheet.createRow(0);
            headerRow.createCell(0).setCellValue(FALLBACK_CONTENT_HEADER);

            List<String> lines = splitLines(content);
            for (int i = 0; i < lines.size(); i++) {
                Row row = sheet.createRow(i + 1);
                row.createCell(0).setCellValue(lines.get(i));
            }

            sheet.autoSizeColumn(0);
            workbook.write(os);
        }

        log.info("Export XLSX fallback completato -> fileName='{}'", fileName);
        return fileName;
    }

    private ReportTableResponse tryExtractTable(String content) {
        ReportTableResponse table = new ReportTableResponse();
        table.setHeaders(new ArrayList<>());
        table.setRows(new ArrayList<>());

        if (content == null || content.isBlank()) {
            return table;
        }

        List<String> lines = splitLines(content);
        List<String> tableLines = lines.stream()
                .map(String::trim)
                .filter(line -> line.startsWith("|") && line.endsWith("|"))
                .toList();

        if (tableLines.size() < 2) {
            return table;
        }

        List<String> headers = parseMarkdownRow(tableLines.get(0));
        if (headers.isEmpty()) {
            return table;
        }

        int startRowIndex = 1;
        if (tableLines.size() > 1 && isMarkdownSeparatorRow(tableLines.get(1))) {
            startRowIndex = 2;
        }

        List<List<String>> rows = new ArrayList<>();
        for (int i = startRowIndex; i < tableLines.size(); i++) {
            List<String> row = parseMarkdownRow(tableLines.get(i));
            if (!row.isEmpty()) {
                rows.add(normalizeRowSize(row, headers.size()));
            }
        }

        table.setHeaders(headers);
        table.setRows(rows);
        return table;
    }

    private boolean hasTableData(ReportTableResponse table) {
        return table != null
                && table.getHeaders() != null
                && !table.getHeaders().isEmpty()
                && table.getRows() != null
                && !table.getRows().isEmpty();
    }

    private List<String> parseMarkdownRow(String row) {
        String trimmed = row.trim();

        if (trimmed.startsWith("|")) {
            trimmed = trimmed.substring(1);
        }

        if (trimmed.endsWith("|")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }

        if (trimmed.isBlank()) {
            return List.of();
        }

        String[] parts = trimmed.split("\\|", -1);
        List<String> values = new ArrayList<>();
        for (String part : parts) {
            values.add(part.trim());
        }
        return values;
    }

    private boolean isMarkdownSeparatorRow(String row) {
        String normalized = row.replace("|", "")
                .replace("-", "")
                .replace(":", "")
                .trim();
        return normalized.isEmpty();
    }

    private List<String> normalizeRowSize(List<String> row, int expectedSize) {
        List<String> normalized = new ArrayList<>(row);

        while (normalized.size() < expectedSize) {
            normalized.add("");
        }

        if (normalized.size() > expectedSize) {
            return new ArrayList<>(normalized.subList(0, expectedSize));
        }

        return normalized;
    }

    private int inferMaxColumns(ReportTableResponse table) {
        if (table == null || table.getRows() == null || table.getRows().isEmpty()) {
            return 1;
        }

        return table.getRows().stream()
                .mapToInt(List::size)
                .max()
                .orElse(1);
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

    private List<String> splitParagraphs(String content) {
        if (content == null || content.isBlank()) {
            return List.of(NO_CONTENT_MESSAGE);
        }

        String normalized = content.replace("\r", "");
        String[] paragraphs = normalized.split("\\n\\s*\\n");

        List<String> result = new ArrayList<>();
        for (String paragraph : paragraphs) {
            String trimmed = paragraph.trim();
            if (!trimmed.isBlank()) {
                result.add(trimmed);
            }
        }

        if (result.isEmpty()) {
            result.add(NO_CONTENT_MESSAGE);
        }

        return result;
    }

    private String escapeCsv(String value) {
        String safe = nullSafe(value).replace("\"", "\"\"");
        return "\"" + safe + "\"";
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private String normalizeFormat(String format) {
        if (format == null || format.isBlank()) {
            return FORMAT_JSON;
        }
        return format.trim().toUpperCase();
    }
}