package com.claude.reportAi.dto;

import lombok.Builder;
import lombok.Data;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class AssembledContext {
    @Builder.Default
    private List<Document> knowledgeBaseDocuments = new ArrayList<>();

    @Builder.Default
    private List<Document> temporaryDocuments = new ArrayList<>();

    @Builder.Default
    private List<Document> referenceDataDocuments = new ArrayList<>();

    @Builder.Default
    private List<String> webResults = new ArrayList<>();

    private String templateDescription;
}