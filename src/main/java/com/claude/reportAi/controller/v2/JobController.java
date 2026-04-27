package com.claude.reportAi.controller.v2;

import com.claude.reportAi.dto.v2.JobStatusResponse;
import com.claude.reportAi.dto.v2.WorkflowInfoResponse;
import com.claude.reportAi.entities.Job;
import com.claude.reportAi.pipeline.PipelineEngine;
import com.claude.reportAi.pipeline.WorkflowDefinition;
import com.claude.reportAi.pipeline.WorkflowRegistry;
import com.claude.reportAi.sector.SectorProfileRegistry;
import com.claude.reportAi.utils.JwtTokenProvider;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * V2 unified job API.
 *
 * A single endpoint replaces the three dedicated v1 endpoints:
 *   POST /api/v2/jobs           — submit any workflow
 *   GET  /api/v2/jobs/{id}      — poll status
 *   GET  /api/v2/jobs/{id}/result — download result
 *   POST /api/v2/jobs/{id}/cancel — cancel
 *   GET  /api/v2/workflows       — list available workflows
 *   GET  /api/v2/sectors         — list available sectors
 */
@RestController
@RequestMapping("/api/v2")
@RequiredArgsConstructor
@Slf4j
public class JobController {

    private final PipelineEngine pipelineEngine;
    private final WorkflowRegistry workflowRegistry;
    private final SectorProfileRegistry sectorProfileRegistry;
    private final JwtTokenProvider jwtTokenProvider;

    // -------------------------------------------------------------------------
    // Submit
    // -------------------------------------------------------------------------

    @PostMapping(value = "/jobs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN', 'USER', 'ANALYST')")
    public ResponseEntity<Map<String, String>> submit(
            @RequestPart("files") List<MultipartFile> files,
            @RequestParam("workflowId") String workflowId,
            @RequestParam(value = "sector", defaultValue = "OIL_GAS") String sector,
            @RequestParam("model") String model,
            HttpServletRequest request) throws IOException {

        UUID userId = extractUserId(request);

        List<byte[]> fileContents = new ArrayList<>();
        List<String> filenames = new ArrayList<>();

        for (MultipartFile file : files) {
            fileContents.add(file.getBytes());
            filenames.add(file.getOriginalFilename() != null ? file.getOriginalFilename() : "file.pdf");
        }

        UUID jobId = pipelineEngine.submit(workflowId, sector, model, fileContents, filenames, userId);

        log.info("v2 job submitted | jobId={} | workflow={} | sector={} | files={}",
                jobId, workflowId, sector, files.size());

        return ResponseEntity.accepted().body(Map.of("jobId", jobId.toString()));
    }

    // -------------------------------------------------------------------------
    // Status
    // -------------------------------------------------------------------------

    @GetMapping("/jobs/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER', 'ANALYST')")
    public ResponseEntity<JobStatusResponse> getStatus(@PathVariable UUID id) {
        Job job = pipelineEngine.getStatus(id);

        WorkflowInfoResponse wf = workflowRegistry.find(job.getWorkflowId())
                .map(w -> WorkflowInfoResponse.builder()
                        .id(w.id())
                        .displayName(w.displayName())
                        .build())
                .orElse(null);

        JobStatusResponse response = JobStatusResponse.builder()
                .jobId(job.getId())
                .workflowId(job.getWorkflowId())
                .workflowDisplayName(wf != null ? wf.getDisplayName() : job.getWorkflowId())
                .sector(job.getSector())
                .status(job.getStatus().name())
                .progress(job.getProgress())
                .currentStep(job.getCurrentStep())
                .errorMessage(job.getErrorMessage())
                .model(job.getModel())
                .inputFilenames(job.getInputFilenames())
                .numFiles(job.getNumFiles())
                .createdAt(job.getCreatedAt())
                .updatedAt(job.getUpdatedAt())
                .build();

        return ResponseEntity.ok(response);
    }

    // -------------------------------------------------------------------------
    // Result download
    // -------------------------------------------------------------------------

    @GetMapping("/jobs/{id}/result")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER', 'ANALYST')")
    public ResponseEntity<byte[]> getResult(@PathVariable UUID id) {
        byte[] content = pipelineEngine.getResult(id);

        String filename = "result-" + id + "-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                + ".docx";

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .body(content);
    }

    // -------------------------------------------------------------------------
    // Cancel
    // -------------------------------------------------------------------------

    @PostMapping("/jobs/{id}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER', 'ANALYST')")
    public ResponseEntity<Map<String, String>> cancel(@PathVariable UUID id) {
        pipelineEngine.cancel(id);
        return ResponseEntity.ok(Map.of("status", "CANCELLED", "jobId", id.toString()));
    }

    // -------------------------------------------------------------------------
    // Catalogue endpoints
    // -------------------------------------------------------------------------

    @GetMapping("/workflows")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER', 'ANALYST')")
    public ResponseEntity<List<WorkflowInfoResponse>> listWorkflows(
            @RequestParam(value = "sector", required = false) String sector) {

        List<WorkflowDefinition> workflows = sector != null
                ? workflowRegistry.getForSector(sector)
                : workflowRegistry.getAll();

        List<WorkflowInfoResponse> result = workflows.stream()
                .map(w -> WorkflowInfoResponse.builder()
                        .id(w.id())
                        .displayName(w.displayName())
                        .description(w.description())
                        .supportedSectors(w.supportedSectors())
                        .minFiles(w.minFiles())
                        .maxFiles(w.maxFiles())
                        .acceptedMimeType(w.acceptedMimeType())
                        .build())
                .toList();

        return ResponseEntity.ok(result);
    }

    @GetMapping("/sectors")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER', 'ANALYST')")
    public ResponseEntity<List<Map<String, Object>>> listSectors() {
        List<Map<String, Object>> result = sectorProfileRegistry.getAll().stream()
                .map(s -> Map.<String, Object>of(
                        "id", s.id(),
                        "displayName", s.displayName(),
                        "description", s.description(),
                        "enabledWorkflows", s.enabledWorkflows()))
                .toList();
        return ResponseEntity.ok(result);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private UUID extractUserId(HttpServletRequest request) {
        try {
            String header = request.getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                String token = header.substring(7);
                String email = jwtTokenProvider.getUserEmailFromToken(token);
                return UUID.nameUUIDFromBytes(email.getBytes());
            }
        } catch (Exception ignored) {}
        return null;
    }
}
