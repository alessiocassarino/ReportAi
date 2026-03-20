package com.claude.reportAi.controller;

import com.claude.reportAi.dto.ReportRequest;
import com.claude.reportAi.dto.ReportResponse;
import com.claude.reportAi.service.ReportExportService;
import com.claude.reportAi.service.ReportOrchestratorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reports")
@Slf4j
public class ReportController {

    @Autowired
    private ReportOrchestratorService reportOrchestratorService;

    @Autowired
    private ReportExportService reportExportService;

    @PostMapping("/generate")
    public ResponseEntity<ReportResponse> generate(@RequestBody ReportRequest request) {
        log.info("Ricevuta richiesta POST /api/reports/generate");
        log.info("Parametri request -> prompt='{}', format='{}', allowWebSearch={}",
                safe(request.getPrompt()),
                request.getFormat(),
                request.isAllowWebSearch());

        ReportResponse response = reportOrchestratorService.generate(request);

        log.info("Generazione completata -> status='{}', foundInKnowledgeBase={}, webSearchUsed={}, fileName='{}', downloadUrl='{}'",
                response.getStatus(),
                response.isFoundInKnowledgeBase(),
                response.isWebSearchUsed(),
                response.getFileName(),
                response.getDownloadUrl());

        return ResponseEntity.ok(response);
    }

    @GetMapping("/download/{fileName}")
    public ResponseEntity<Resource> download(@PathVariable String fileName) {
        log.info("Ricevuta richiesta download file -> fileName='{}'", fileName);

        Resource resource = reportExportService.loadAsResource(fileName);
        String contentType = reportExportService.resolveContentType(fileName);

        log.info("Download pronto -> fileName='{}', contentType='{}', resourceExists={}",
                fileName,
                contentType,
                resource != null && resource.exists());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(fileName).build().toString())
                .contentType(MediaType.parseMediaType(contentType))
                .body(resource);
    }

    private String safe(String text) {
        if (text == null) {
            return null;
        }
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }
}