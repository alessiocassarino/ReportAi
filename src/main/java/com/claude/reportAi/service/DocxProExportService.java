package com.claude.reportAi.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

@Service
@Slf4j
public class DocxProExportService {

    private static final Pattern HEADING_PATTERN = Pattern.compile("^#+\\s+(.+)$");
    private static final Pattern BULLET_PATTERN = Pattern.compile("^[\\-\\*\\•]\\s+(.+)$");
    private static final Pattern NUMBERED_PATTERN = Pattern.compile("^\\d+\\.\\s+(.+)$");

    public String exportDocxPro(String content, Path exportDir) throws IOException {
        String fileName = java.util.UUID.randomUUID() + ".docx";
        Path path = exportDir.resolve(fileName);

        try (XWPFDocument document = new XWPFDocument();
             OutputStream os = Files.newOutputStream(path)) {

            // 1. Add document metadata
            try {
                document.getProperties().getCoreProperties().setTitle("Report");
                document.getProperties().getCoreProperties().setCreator("ReportAI");
                Calendar cal = Calendar.getInstance();
                document.getProperties().getCoreProperties().setCreated(String.valueOf(cal));
            } catch (Exception e) {
                log.warn("Could not set document properties: {}", e.getMessage());
            }

            // 2. Parse content structure
            DocumentStructure structure = parseDocumentStructure(content);

            // 3. Add title
            if (structure.getTitle() != null && !structure.getTitle().isBlank()) {
                XWPFParagraph titlePara = document.createParagraph();
                titlePara.setStyle("Heading1");
                XWPFRun titleRun = titlePara.createRun();
                titleRun.setText(structure.getTitle());
                titleRun.setBold(true);
                titleRun.setFontSize(24);
                titleRun.setColor("1F4E78");
            }

            // 4. Add table of contents if multiple sections
            if (structure.getSections().size() > 3) {
                addTableOfContents(document, structure);
                XWPFParagraph pageBreak = document.createParagraph();
                pageBreak.setPageBreak(true);
            }

            // 5. Add sections
            for (DocumentSection section : structure.getSections()) {
                // Section heading
                XWPFParagraph headingPara = document.createParagraph();
                headingPara.setStyle("Heading2");
                XWPFRun headingRun = headingPara.createRun();
                headingRun.setText(section.getTitle());
                headingRun.setBold(true);
                headingRun.setFontSize(16);
                headingRun.setColor("2E5C8A");

                // Section content
                addSectionContent(document, section);

                // Add spacing
                document.createParagraph();
            }

            // 6. Add footer with timestamp
            addFooter(document);

            document.write(os);

            log.info("DOCX Pro export completed -> file={}, sections={}",
                    fileName, structure.getSections().size());

            return fileName;

        } catch (IOException e) {
            log.error("Error during DOCX export: {}", e.getMessage(), e);
            throw new IOException("Failed to export DOCX", e);
        }
    }

    private DocumentStructure parseDocumentStructure(String content) {
        DocumentStructure structure = new DocumentStructure();

        String[] paragraphs = content.split("\n\n");

        // Extract title from first line if short
        if (paragraphs.length > 0) {
            String firstLine = paragraphs[0].trim();
            if (firstLine.length() < 100 && !firstLine.contains(".") && firstLine.contains("#")) {
                structure.setTitle(firstLine.replaceAll("^#+\\s*", "").trim());
                paragraphs = Arrays.copyOfRange(paragraphs, 1, paragraphs.length);
            }
        }

        // Group into sections
        List<DocumentSection> sections = new ArrayList<>();
        List<String> currentLines = new ArrayList<>();
        String currentTitle = "Overview";

        for (String paragraph : paragraphs) {
            String[] lines = paragraph.split("\n");
            for (String line : lines) {
                if (line.trim().startsWith("#")) {
                    // New section
                    if (!currentLines.isEmpty()) {
                        sections.add(new DocumentSection(currentTitle, new ArrayList<>(currentLines)));
                        currentLines.clear();
                    }
                    currentTitle = line.replaceAll("^#+\\s*", "").trim();
                } else if (!line.trim().isBlank()) {
                    currentLines.add(line.trim());
                }
            }
        }

        if (!currentLines.isEmpty()) {
            sections.add(new DocumentSection(currentTitle, currentLines));
        }

        structure.setSections(sections);
        return structure;
    }

    private void addSectionContent(XWPFDocument document, DocumentSection section) {
        for (String line : section.getLines()) {
            if (BULLET_PATTERN.matcher(line).matches()) {
                // Bullet list
                XWPFParagraph bulletPara = document.createParagraph();
                bulletPara.setIndentationLeft(720);
                bulletPara.setIndentationHanging(360);
                XWPFRun bulletRun = bulletPara.createRun();
                bulletRun.setText("• " + line.replaceAll("^[\\-\\*\\•]\\s+", "").trim());
            } else if (NUMBERED_PATTERN.matcher(line).matches()) {
                // Numbered list
                XWPFParagraph numberedPara = document.createParagraph();
                numberedPara.setIndentationLeft(720);
                XWPFRun numberedRun = numberedPara.createRun();
                numberedRun.setText(line.trim());
            } else {
                // Regular paragraph
                XWPFParagraph contentPara = document.createParagraph();
                contentPara.setSpacingBetween(1.15);
                XWPFRun contentRun = contentPara.createRun();
                contentRun.setText(line);
                contentRun.setFontSize(11);
            }
        }
    }

    private void addTableOfContents(XWPFDocument document, DocumentStructure structure) {
        XWPFParagraph tocTitlePara = document.createParagraph();
        tocTitlePara.setStyle("Heading1");
        XWPFRun tocTitleRun = tocTitlePara.createRun();
        tocTitleRun.setText("Table of Contents");
        tocTitleRun.setBold(true);
        tocTitleRun.setFontSize(16);

        for (DocumentSection section : structure.getSections()) {
            XWPFParagraph itemPara = document.createParagraph();
            itemPara.setIndentationLeft(720);
            XWPFRun itemRun = itemPara.createRun();
            itemRun.setText("• " + section.getTitle());
            itemRun.setFontSize(11);
        }
    }

    private void addFooter(XWPFDocument document) {
        try {
            // Add footer to all sections
            XWPFFooter footer = document.createFooter(HeaderFooterType.DEFAULT);
            
            XWPFParagraph footerPara = footer.createParagraph();
            footerPara.setAlignment(ParagraphAlignment.CENTER);
            
            XWPFRun footerRun = footerPara.createRun();
            footerRun.setText("Generated by ReportAI - " + LocalDateTime.now().format(
                    DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
            ));
            footerRun.setFontSize(9);
            footerRun.setItalic(true);
            
            log.debug("Footer added successfully");
        } catch (Exception e) {
            log.warn("Could not add footer: {}", e.getMessage());
            // Continue without footer - not critical
        }
    }

    private static class DocumentStructure {
        private String title;
        private List<DocumentSection> sections = new ArrayList<>();

        void setTitle(String title) {
            this.title = title;
        }

        String getTitle() {
            return title;
        }

        void setSections(List<DocumentSection> sections) {
            this.sections = sections;
        }

        List<DocumentSection> getSections() {
            return sections;
        }
    }

    private static class DocumentSection {
        private final String title;
        private final List<String> lines;

        DocumentSection(String title, List<String> lines) {
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
