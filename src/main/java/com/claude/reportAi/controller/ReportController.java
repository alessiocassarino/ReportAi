package com.claude.reportAi.controller;

import com.claude.reportAi.entities.GeneratedReport;
import com.claude.reportAi.repository.GeneratedReportRepository;
import com.claude.reportAi.service.SecureReportService;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final SecureReportService reportService;
    private final GeneratedReportRepository reportRepository;

    public ReportController(SecureReportService reportService,
                            GeneratedReportRepository reportRepository) {
        this.reportService = reportService;
        this.reportRepository = reportRepository;
    }

    /**
     * POST /api/reports/generate
     * Body: { "clientId": "CLIENT_123", "prompt": "Crea un report...", "type": "XLSX" }
     *
     * Genera il report, lo salva nel DB, lo cancella da Anthropic.
     */
    @PostMapping("/generate")
    public ResponseEntity<?> generate(@RequestBody ReportRequest request) {
        try {
            AnthropicApi.AnthropicSkill skill = AnthropicApi.AnthropicSkill.valueOf(request.type().toUpperCase());

            List<GeneratedReport> reports = reportService.generateAndSecure(
                    request.clientId(),
                    request.prompt(),
                    skill
            );

            return ResponseEntity.ok(reports.stream().map(r -> new ReportResponse(
                    r.getId(),
                    r.getFileName(),
                    r.getFileSize(),
                    r.isDeletedFromAnthropic(),
                    "Report generato e salvato nel database. " +
                            "File rimosso dai server Anthropic: " + r.isDeletedFromAnthropic()
            )).toList());

        } catch (Exception e) {
            return ResponseEntity.status(500).body(
                    Map.of("error", e.getMessage())
            );
        }
    }

    /**
     * GET /api/reports/{id}/download
     *
     * Scarica il file DAL NOSTRO DATABASE (non da Anthropic).
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable Long id) {
        GeneratedReport report = reportRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Report non trovato"));

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + report.getFileName() + "\"")
                .contentType(MediaType.parseMediaType(report.getMimeType()))
                .contentLength(report.getFileSize())
                .body(report.getFileContent());
    }

    /**
     * GET /api/reports/client/{clientId}
     *
     * Lista tutti i report di un cliente.
     */
    @GetMapping("/client/{clientId}")
    public List<ReportResponse> listByClient(@PathVariable String clientId) {
        return reportRepository.findByClientId(clientId).stream()
                .map(r -> new ReportResponse(
                        r.getId(), r.getFileName(), r.getFileSize(),
                        r.isDeletedFromAnthropic(), null
                )).toList();
    }

    // DTOs
    record ReportRequest(String clientId, String prompt, String type) {}
    record ReportResponse(Long id, String fileName, long fileSize,
                          boolean deletedFromAnthropic, String message) {}
}
