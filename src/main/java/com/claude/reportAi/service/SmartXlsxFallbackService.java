package com.claude.reportAi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class SmartXlsxFallbackService {

    private static final Pattern HEADING_PATTERN = Pattern.compile("^#+\\s+(.+)$");
    private static final int HEADING_FONT_SIZE = 14;
    private static final int CONTENT_FONT_SIZE = 11;

    public String exportFallbackXlsx(String content, Path exportDir) throws IOException {
        String fileName = UUID.randomUUID() + ".xlsx";
        Path path = exportDir.resolve(fileName);

        try (Workbook workbook = new XSSFWorkbook();
             OutputStream os = Files.newOutputStream(path)) {

            Sheet sheet = workbook.createSheet("Report");

            // 1. Parse content structure
            ContentStructure structure = parseContentStructure(content);

            int currentRow = 0;

            // 2. Write title (if detected)
            if (structure.getTitle() != null && !structure.getTitle().isBlank()) {
                Row titleRow = sheet.createRow(currentRow++);
                Cell titleCell = titleRow.createCell(0);
                titleCell.setCellValue(structure.getTitle());
                CellStyle titleStyle = createTitleStyle(workbook);
                titleCell.setCellStyle(titleStyle);

                if (sheet.getNumMergedRegions() < 100) {
                    sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(currentRow - 1, currentRow - 1, 0, 2));
                }
                currentRow++;
            }

            // 3. Write sections
            for (ContentSection section : structure.getSections()) {
                // Section title
                Row sectionRow = sheet.createRow(currentRow++);
                Cell sectionCell = sectionRow.createCell(0);
                sectionCell.setCellValue(section.getTitle());
                CellStyle sectionStyle = createSectionStyle(workbook);
                sectionCell.setCellStyle(sectionStyle);

                if (sheet.getNumMergedRegions() < 100) {
                    sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(currentRow - 1, currentRow - 1, 0, 2));
                }

                // Section content
                CellStyle contentStyle = createContentStyle(workbook);
                contentStyle.setWrapText(true);

                for (String line : section.getLines()) {
                    if (line.isBlank()) continue;

                    Row contentRow = sheet.createRow(currentRow++);
                    Cell contentCell = contentRow.createCell(0);
                    contentCell.setCellValue(line);
                    contentCell.setCellStyle(contentStyle);

                    // Set row height for wrapped text
                    contentRow.setHeightInPoints((short) 30);
                }

                currentRow++; // Blank line between sections
            }

            // 4. Format columns
            sheet.setColumnWidth(0, 5000);
            sheet.autoSizeColumn(0);

            // 5. Set default row height
            sheet.setDefaultRowHeightInPoints(15);

            workbook.write(os);

            log.info("Smart XLSX fallback export completed -> file={}, sections={}",
                    fileName, structure.getSections().size());

            return fileName;

        } catch (IOException e) {
            log.error("Error during smart XLSX fallback export: {}", e.getMessage(), e);
            throw new IOException("Failed to export XLSX fallback", e);
        }
    }

    private ContentStructure parseContentStructure(String content) {
        ContentStructure structure = new ContentStructure();

        String[] paragraphs = content.split("\n\n");

        // First paragraph = title (if short)
        if (paragraphs.length > 0 && paragraphs[0].length() < 100 && !paragraphs[0].contains(".")) {
            structure.setTitle(paragraphs[0].trim());
            paragraphs = Arrays.copyOfRange(paragraphs, 1, paragraphs.length);
        }

        // Remaining paragraphs = sections
        for (String paragraph : paragraphs) {
            ContentSection section = parseSection(paragraph);
            structure.addSection(section);
        }

        return structure;
    }

    private ContentSection parseSection(String paragraph) {
        String[] lines = paragraph.split("\n");

        // First line = title (if starts with #)
        String title = "Content";
        int startIdx = 0;

        if (lines.length > 0 && lines[0].matches("^#+\\s+.+$")) {
            title = lines[0].replaceAll("^#+\\s*", "").trim();
            startIdx = 1;
        }

        List<String> content = new ArrayList<>();
        for (int i = startIdx; i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                content.add(lines[i].trim());
            }
        }

        return new ContentSection(title, content);
    }

    private CellStyle createTitleStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 18);
        font.setColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    private CellStyle createSectionStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) HEADING_FONT_SIZE);
        font.setColor(IndexedColors.BLUE.getIndex());
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.LEFT);
        style.setVerticalAlignment(VerticalAlignment.TOP);
        style.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return style;
    }

    private CellStyle createContentStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setFontHeightInPoints((short) CONTENT_FONT_SIZE);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.LEFT);
        style.setVerticalAlignment(VerticalAlignment.TOP);
        style.setWrapText(true);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        return style;
    }

    private static class ContentStructure {
        private String title;
        private List<ContentSection> sections = new ArrayList<>();

        void setTitle(String title) {
            this.title = title;
        }

        String getTitle() {
            return title;
        }

        void addSection(ContentSection section) {
            sections.add(section);
        }

        List<ContentSection> getSections() {
            return sections;
        }
    }

    private static class ContentSection {
        private final String title;
        private final List<String> lines;

        ContentSection(String title, List<String> lines) {
            this.title = title;
            this.lines = lines;
        }

        String getTitle() {
            return title;
        }

        List<String> getLines() {
            return lines;
        }
    }
}
