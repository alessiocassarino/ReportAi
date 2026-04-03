package com.claude.reportAi.controller;

import com.claude.reportAi.dto.HistoryDTO;
import com.claude.reportAi.service.HistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/jobs")
@RequiredArgsConstructor
public class HistoryController {

    private final HistoryService HistoryService;

    @GetMapping
    public ResponseEntity<Page<HistoryDTO>> getJobs(
            @RequestParam(defaultValue = "0")   int page,
            @RequestParam(defaultValue = "25")  int size,
            @RequestParam(required = false)     String type,
            @RequestParam(required = false)     String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(required = false)     String search,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "DESC")      String sortDir) {

        Page<HistoryDTO> result = HistoryService.getJobs(
                page, size, type, status, dateFrom, dateTo, search, sortBy, sortDir);

        return ResponseEntity.ok(result);
    }
}
