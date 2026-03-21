package com.claude.reportAi.service;

import com.claude.reportAi.constant.AttachmentRole;
import com.claude.reportAi.dto.AttachmentMetadataRequest;
import com.claude.reportAi.dto.DocumentUploadResponse;
import com.claude.reportAi.dto.ProcessedAttachment;
import com.claude.reportAi.entities.ReportTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class AttachmentProcessingService {

    private final TemporaryFileService temporaryFileService;
    private final TemplateService templateService;
    private final StoredFileService storedFileService;
    private final AttachmentContentExtractionService attachmentContentExtractionService;

    public List<ProcessedAttachment> processAttachments(Path requestTempDir,
                                                        List<MultipartFile> files,
                                                        List<AttachmentMetadataRequest> attachmentsMetadata) {
        if (files == null || files.isEmpty()) {
            return List.of();
        }

        List<ProcessedAttachment> results = new ArrayList<>();

        for (MultipartFile file : files) {
            AttachmentMetadataRequest metadataRequest = findMetadataForFile(file, attachmentsMetadata);
            AttachmentRole role = metadataRequest != null && metadataRequest.getRole() != null
                    ? metadataRequest.getRole()
                    : AttachmentRole.TEMP_CONTEXT;

            log.info("Processing attachment -> filename='{}', role={}", file.getOriginalFilename(), role);

            ProcessedAttachment processedAttachment = switch (role) {
                case TEMPLATE -> processTemplate(file, metadataRequest);
                case PERSISTENT_CONTEXT -> processPersistentContext(requestTempDir, file, metadataRequest);
                case REFERENCE_DATA -> processTemporaryContext(requestTempDir, file, role);
                case TEMP_CONTEXT -> processTemporaryContext(requestTempDir, file, role);
            };

            results.add(processedAttachment);
        }

        return results;
    }

    private ProcessedAttachment processTemplate(MultipartFile file, AttachmentMetadataRequest metadataRequest) {
        ReportTemplate savedTemplate = templateService.saveTemplate(file, metadataRequest);

        return ProcessedAttachment.builder()
                .requestAttachmentId(UUID.randomUUID())
                .originalFilename(file.getOriginalFilename())
                .contentType(file.getContentType())
                .role(AttachmentRole.TEMPLATE)
                .temporary(false)
                .template(true)
                .savedTemplateId(savedTemplate.getId())
                .persistedToKnowledgeBase(false)
                .documents(List.of())
                .build();
    }

    private ProcessedAttachment processPersistentContext(Path requestTempDir,
                                                         MultipartFile file,
                                                         AttachmentMetadataRequest metadataRequest) {
        Path tempPath = temporaryFileService.saveTempFile(requestTempDir, file);

        String extractedText = attachmentContentExtractionService.extractText(tempPath, file.getContentType());

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("attachmentRole", AttachmentRole.PERSISTENT_CONTEXT.name());
        metadata.put("sourceType", "request_attachment");

        List<Document> documents = attachmentContentExtractionService.buildDocuments(
                tempPath,
                file.getOriginalFilename(),
                file.getContentType(),
                extractedText,
                metadata
        );

        DocumentUploadResponse uploadResponse = storedFileService.ingest(file);

        UUID savedStoredFileId = uploadResponse.getId();

        return ProcessedAttachment.builder()
                .requestAttachmentId(UUID.randomUUID())
                .originalFilename(file.getOriginalFilename())
                .contentType(file.getContentType())
                .role(AttachmentRole.PERSISTENT_CONTEXT)
                .storedPath(tempPath)
                .temporary(true)
                .extractedText(extractedText)
                .documents(documents)
                .savedStoredFileId(savedStoredFileId)
                .persistedToKnowledgeBase(true)
                .template(false)
                .build();
    }

    private ProcessedAttachment processTemporaryContext(Path requestTempDir,
                                                        MultipartFile file,
                                                        AttachmentRole role) {
        Path tempPath = temporaryFileService.saveTempFile(requestTempDir, file);

        String extractedText = attachmentContentExtractionService.extractText(tempPath, file.getContentType());

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("attachmentRole", role.name());
        metadata.put("sourceType", "request_attachment");

        List<Document> documents = attachmentContentExtractionService.buildDocuments(
                tempPath,
                file.getOriginalFilename(),
                file.getContentType(),
                extractedText,
                metadata
        );

        return ProcessedAttachment.builder()
                .requestAttachmentId(UUID.randomUUID())
                .originalFilename(file.getOriginalFilename())
                .contentType(file.getContentType())
                .role(role)
                .storedPath(tempPath)
                .temporary(true)
                .extractedText(extractedText)
                .documents(documents)
                .persistedToKnowledgeBase(false)
                .template(false)
                .build();
    }

    private AttachmentMetadataRequest findMetadataForFile(MultipartFile file,
                                                          List<AttachmentMetadataRequest> attachmentsMetadata) {
        if (attachmentsMetadata == null || attachmentsMetadata.isEmpty()) {
            return null;
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null) {
            return null;
        }

        return attachmentsMetadata.stream()
                .filter(item -> item.getClientFileName() != null)
                .filter(item -> item.getClientFileName().equalsIgnoreCase(originalFilename))
                .findFirst()
                .orElse(null);
    }
}