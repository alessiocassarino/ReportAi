package com.claude.reportAi.service;

import com.claude.reportAi.constant.AttachmentRole;
import com.claude.reportAi.dto.AssembledContext;
import com.claude.reportAi.dto.ProcessedAttachment;
import com.claude.reportAi.entities.ReportTemplate;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ContextAssemblyService {

    public AssembledContext assemble(List<Document> knowledgeBaseDocuments,
                                     List<ProcessedAttachment> processedAttachments,
                                     List<String> webResults,
                                     ReportTemplate selectedTemplate) {

        List<Document> temporaryDocuments = new ArrayList<>();
        List<Document> referenceDataDocuments = new ArrayList<>();

        if (processedAttachments != null) {
            for (ProcessedAttachment processedAttachment : processedAttachments) {
                if (processedAttachment.getDocuments() == null || processedAttachment.getDocuments().isEmpty()) {
                    continue;
                }

                if (processedAttachment.getRole() == AttachmentRole.TEMP_CONTEXT) {
                    temporaryDocuments.addAll(processedAttachment.getDocuments());
                }

                if (processedAttachment.getRole() == AttachmentRole.REFERENCE_DATA) {
                    referenceDataDocuments.addAll(processedAttachment.getDocuments());
                }

                if (processedAttachment.getRole() == AttachmentRole.PERSISTENT_CONTEXT) {
                    temporaryDocuments.addAll(processedAttachment.getDocuments());
                }
            }
        }

        String templateDescription = null;
        if (selectedTemplate != null) {
            templateDescription = "Template selezionato: id=%d, name=%s, type=%s, filename=%s"
                    .formatted(
                            selectedTemplate.getId(),
                            selectedTemplate.getName(),
                            selectedTemplate.getTemplateType().name(),
                            selectedTemplate.getOriginalFilename()
                    );
        }

        return AssembledContext.builder()
                .knowledgeBaseDocuments(knowledgeBaseDocuments != null ? knowledgeBaseDocuments : List.of())
                .temporaryDocuments(temporaryDocuments)
                .referenceDataDocuments(referenceDataDocuments)
                .webResults(webResults != null ? webResults : List.of())
                .templateDescription(templateDescription)
                .build();
    }
}