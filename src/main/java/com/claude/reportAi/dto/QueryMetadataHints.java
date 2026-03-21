package com.claude.reportAi.dto;

import com.claude.reportAi.constant.DocumentType;
import lombok.Builder;
import lombok.Data;

import java.util.Set;

@Data
@Builder
public class QueryMetadataHints {
    private Set<DocumentType> preferredDocumentTypes;
    private Set<String> countries;
    private Set<String> clients;
    private Set<String> sectors;
    private Set<String> contractTypes;
    private Set<String> keywords;
}
