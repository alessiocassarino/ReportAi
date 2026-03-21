package com.claude.reportAi.dto;

import com.claude.reportAi.constant.AttachmentRole;
import lombok.Builder;
import lombok.Data;
import org.springframework.ai.document.Document;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class ProcessedAttachment {
    private UUID requestAttachmentId;
    private String originalFilename;
    private String contentType;
    private AttachmentRole role;

    private Path storedPath;
    private boolean temporary;

    private String extractedText;

    @Builder.Default
    private List<Document> documents = new ArrayList<>();

    private Long savedTemplateId;
    private UUID savedStoredFileId;

    private boolean template;
    private boolean persistedToKnowledgeBase;
}