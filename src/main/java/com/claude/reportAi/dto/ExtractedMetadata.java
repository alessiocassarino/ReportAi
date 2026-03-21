package com.claude.reportAi.dto;

import com.claude.reportAi.constant.DocumentType;
import com.claude.reportAi.constant.Language;
import lombok.*;

import java.time.LocalDate;
import java.util.List;

@Data
@Builder
public class ExtractedMetadata {
    private DocumentType documentType;
    private Language language;
    private String country;
    private String clientName;
    private String projectName;
    private String sector;
    private String contractType;
    private LocalDate documentDate;
    private String documentVersion;
    private List<String> tags;
}
