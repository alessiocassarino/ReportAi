package com.claude.reportAi.service;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Service
public class ReportExportService {

    @Value("${app.reports.root:./data/reports}")
    private String reportsRoot;

    public String export(String content, String format) {
        try {
            Files.createDirectories(Path.of(reportsRoot));

            if ("CSV".equalsIgnoreCase(format)) {
                String fileName = UUID.randomUUID() + ".csv";
                Path path = Path.of(reportsRoot, fileName);
                Files.writeString(path, escapeCsv(content));
                return fileName;
            }

            if ("XLSX".equalsIgnoreCase(format)) {
                String fileName = UUID.randomUUID() + ".xlsx";
                Path path = Path.of(reportsRoot, fileName);

                try (XSSFWorkbook workbook = new XSSFWorkbook();
                     OutputStream os = Files.newOutputStream(path)) {
                    var sheet = workbook.createSheet("report");
                    Row row = sheet.createRow(0);
                    row.createCell(0).setCellValue("Contenuto Report");

                    Row contentRow = sheet.createRow(1);
                    contentRow.createCell(0).setCellValue(content);

                    workbook.write(os);
                }
                return fileName;
            }

            throw new IllegalArgumentException("Formato non supportato: " + format);
        } catch (Exception e) {
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
        if (fileName.toLowerCase().endsWith(".csv")) {
            return "text/csv";
        }

        if (fileName.toLowerCase().endsWith(".xlsx")) {
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        }

        return "application/octet-stream";
    }

    private String escapeCsv(String content) {
        String safe = content.replace("\"", "\"\"");
        return "\"" + safe + "\"";
    }
}