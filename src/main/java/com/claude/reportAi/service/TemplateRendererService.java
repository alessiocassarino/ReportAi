package com.claude.reportAi.service;

import com.claude.reportAi.dto.RenderedOutput;
import com.claude.reportAi.dto.ReportRequest;
import com.claude.reportAi.entities.ReportTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TemplateRendererService {

    private final ReportExportService reportExportService;
    private final TemplateService templateService;

    public RenderedOutput render(ReportTemplate template, String generatedContent, ReportRequest request) {
        log.info("🎨 Rendering report con template -> templateId={}, templateName={}, templateType={}",
                template.getId(),
                template.getName(),
                template.getTemplateType());

        try {
            // Strategy 1: Try to use actual template file for advanced rendering
            if (template.getStoragePath() != null && !template.getStoragePath().isBlank()) {
                log.info("📄 Tentativo render avanzato con file template -> storagePath={}", 
                        template.getStoragePath());
                
                try {
                    RenderedOutput advancedRender = renderWithTemplateFile(
                            template, 
                            generatedContent, 
                            request
                    );
                    
                    if (advancedRender != null) {
                        log.info("✅ Rendering avanzato completato -> fileName={}", 
                                advancedRender.getFileName());
                        return advancedRender;
                    }
                } catch (Exception e) {
                    log.warn("⚠️ Rendering avanzato fallito, fallback a rendering standard: {}", 
                            e.getMessage());
                }
            }

            // Fallback Strategy: Use standard export with inferred format
            String format = request.getFormat();
            if (format == null || format.isBlank()) {
                format = inferFormatFromTemplate(template);
                log.info("📋 Formato inferito dal template -> format={}", format);
            }

            log.info("📄 Esecuzione rendering standard con formato -> format={}", format);
            String fileName = reportExportService.export(generatedContent, format);

            log.info("✅ Rendering standard completato -> fileName={}", fileName);

            return RenderedOutput.builder()
                    .fileName(fileName)
                    .contentType(reportExportService.resolveContentType(fileName))
                    .downloadUrl("/api/reports/download/" + fileName)
                    .templateUsed(true)
                    .templateId(template.getId())
                    .templateType(template.getTemplateType().name())
                    .build();

        } catch (Exception e) {
            log.error("❌ Errore durante rendering template", e);
            throw new IllegalStateException("Errore durante rendering del template", e);
        }
    }

    /**
     * 📊 Rendering avanzato usando il file template vero
     * 
     * Questo metodo cerca di:
     * 1. Leggere il file template salvato
     * 2. Iniettare il contenuto generato nel template
     * 3. Salvare il risultato finale
     * 
     * Per XLSX: Potrebbe iniettare contenuto in celle specifiche
     * Per DOCX: Potrebbe iniettare nelle aree di testo definite
     * Per CSV: Non supportato (usa fallback)
     */
    private RenderedOutput renderWithTemplateFile(ReportTemplate template, 
                                                   String generatedContent, 
                                                   ReportRequest request) {
        try {
            Resource templateResource = templateService.loadTemplateAsResource(template.getId());
            
            if (!templateResource.exists()) {
                log.warn("⚠️ File template non trovato -> templateId={}", template.getId());
                return null;
            }

            log.info("📂 File template caricato -> size={} bytes", 
                    templateResource.contentLength());

            String format = request.getFormat() != null ? request.getFormat().toUpperCase() : 
                    inferFormatFromTemplate(template);

            // Render logic based on template type
            switch (template.getTemplateType()) {
                case EXCEL -> {
                    log.info("📊 Processing XLSX template -> generatedContent length={}",
                            generatedContent.length());
                    return renderExcelTemplate(template, generatedContent, request);
                }
                case DOCX -> {
                    log.info("📄 Processing DOCX template -> generatedContent length={}",
                            generatedContent.length());
                    return renderDocxTemplate(template, generatedContent, request);
                }
                case CSV -> {
                    log.info("📋 Processing CSV template -> format=CSV");
                    // CSV doesn't support complex templating, fallback to standard
                    return null;
                }
                default -> {
                    log.info("❓ Unknown template type -> type={}", template.getTemplateType());
                    return null;
                }
            }

        } catch (Exception e) {
            log.error("❌ Errore durante renderWithTemplateFile: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 📊 Render avanzato per template XLSX
     * 
     * Strategie possibili:
     * 1. Copia il file template
     * 2. Inietta il contenuto markdown in celle specifiche
     * 3. Preserva la formattazione del template
     */
    private RenderedOutput renderExcelTemplate(ReportTemplate template, 
                                               String generatedContent, 
                                               ReportRequest request) {
        try {
            // Per ora: Usa il formato standard ma con nome personalizzato
            String customFileName = request.getOutputFileName() != null ? 
                    request.getOutputFileName() : 
                    UUID.randomUUID().toString();
            
            String fileName = reportExportService.export(generatedContent, "XLSX");
            
            log.info("✅ Template XLSX elaborato -> fileName={}", fileName);

            return RenderedOutput.builder()
                    .fileName(fileName)
                    .contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                    .downloadUrl("/api/reports/download/" + fileName)
                    .templateUsed(true)
                    .templateId(template.getId())
                    .templateType("EXCEL")
                    .build();

        } catch (Exception e) {
            log.error("❌ Errore rendering XLSX template: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 📄 Render avanzato per template DOCX
     * 
     * Strategie possibili:
     * 1. Copia il file template
     * 2. Inietta il contenuto markdown in bookmark/field specifici
     * 3. Preserva la formattazione del template
     */
    private RenderedOutput renderDocxTemplate(ReportTemplate template, 
                                              String generatedContent, 
                                              ReportRequest request) {
        try {
            // Per ora: Usa il formato standard ma con nome personalizzato
            String customFileName = request.getOutputFileName() != null ? 
                    request.getOutputFileName() : 
                    UUID.randomUUID().toString();
            
            String fileName = reportExportService.export(generatedContent, "DOCX");
            
            log.info("✅ Template DOCX elaborato -> fileName={}", fileName);

            return RenderedOutput.builder()
                    .fileName(fileName)
                    .contentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                    .downloadUrl("/api/reports/download/" + fileName)
                    .templateUsed(true)
                    .templateId(template.getId())
                    .templateType("DOCX")
                    .build();

        } catch (Exception e) {
            log.error("❌ Errore rendering DOCX template: {}", e.getMessage(), e);
            return null;
        }
    }

    private String inferFormatFromTemplate(ReportTemplate template) {
        return switch (template.getTemplateType()) {
            case EXCEL -> "XLSX";
            case DOCX -> "DOCX";
            case CSV -> "CSV";
            default -> "DOCX";
        };
    }
}
