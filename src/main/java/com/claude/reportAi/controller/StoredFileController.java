package com.claude.reportAi.controller;


import com.claude.reportAi.dto.DocumentUploadResponse;
import com.claude.reportAi.service.StoredFileService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/documents")
public class StoredFileController {

    @Autowired
    private StoredFileService storedFileService;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<List<DocumentUploadResponse>> upload(
            @RequestParam("files") List<MultipartFile> files) {

        List<DocumentUploadResponse> result = files.stream()
                .map(storedFileService::ingest)
                .toList();

        return ResponseEntity.ok(result);
    }
}
