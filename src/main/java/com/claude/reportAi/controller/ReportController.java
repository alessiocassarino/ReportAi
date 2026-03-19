package com.claude.reportAi.controller;

import com.claude.reportAi.dto.ReportRequest;
import com.claude.reportAi.dto.ReportResponse;
import com.claude.reportAi.service.ReportExportService;
import com.claude.reportAi.service.ReportOrchestratorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

    @Autowired
    private  ReportOrchestratorService reportOrchestratorService;

    @Autowired
    private ReportExportService reportExportService;


    @PostMapping("/generate")
    public ResponseEntity<ReportResponse> generate(@RequestBody ReportRequest request) {
        return ResponseEntity.ok(reportOrchestratorService.generate(request));
    }


    @GetMapping("/download/{fileName}")
    public ResponseEntity<Resource> download(@PathVariable String fileName) {
        Resource resource = reportExportService.loadAsResource(fileName);

        String contentType = reportExportService.resolveContentType(fileName);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(fileName).build().toString())
                .contentType(MediaType.parseMediaType(contentType))
                .body(resource);
    }
}
