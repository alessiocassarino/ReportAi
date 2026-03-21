package com.claude.reportAi.service.websearch;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TavilyRequest {
    private String query;
    private String api_key;
    private int max_results;
    private String search_depth;
    private boolean include_domains;
}

