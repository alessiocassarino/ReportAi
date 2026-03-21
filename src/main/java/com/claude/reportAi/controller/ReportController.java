package com.claude.reportAi.controller;

import com.claude.reportAi.dto.GenerateAttachmentsRequest;
import com.claude.reportAi.dto.ReportRequest;
import com.claude.reportAi.dto.ReportResponse;
import com.claude.reportAi.service.ReportExportService;
import com.claude.reportAi.service.ReportOrchestratorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
@Slf4j
public class ReportController {

    private final ReportOrchestratorService reportOrchestratorService;
    private final ReportExportService reportExportService;
    private final ObjectMapper objectMapper;

    @PostMapping(value = "/generate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ReportResponse> generate(
            @RequestPart("request") String requestJson,
            @RequestPart(value = "files", required = false) List<MultipartFile> files,
            @RequestPart(value = "attachments", required = false) String attachmentsJson) {

        try {
            ReportRequest request = objectMapper.readValue(requestJson, ReportRequest.class);

            GenerateAttachmentsRequest generateAttachmentsRequest;
            if (attachmentsJson == null || attachmentsJson.isBlank()) {
                generateAttachmentsRequest = new GenerateAttachmentsRequest();
            } else {
                generateAttachmentsRequest = objectMapper.readValue(attachmentsJson, GenerateAttachmentsRequest.class);
            }

            log.info("Ricevuta richiesta POST /api/reports/generate multipart");
            log.info("Parametri request -> prompt='{}', format='{}', allowWebSearch={}, filesCount={}, attachmentMetadataCount={}",
                    safe(request.getPrompt()),
                    request.getFormat(),
                    request.isAllowWebSearch(),
                    files != null ? files.size() : 0,
                    generateAttachmentsRequest.getAttachments() != null ? generateAttachmentsRequest.getAttachments().size() : 0);

            ReportResponse response = reportOrchestratorService.generate(
                    request,
                    files,
                    generateAttachmentsRequest
            );

            log.info("Generazione completata -> status='{}', foundInKnowledgeBase={}, webSearchUsed={}, fileName='{}', downloadUrl='{}'",
                    response.getStatus(),
                    response.isFoundInKnowledgeBase(),
                    response.isWebSearchUsed(),
                    response.getFileName(),
                    response.getDownloadUrl());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Errore durante parsing multipart generate", e);
            throw new IllegalStateException("Errore durante elaborazione della richiesta multipart", e);
        }
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