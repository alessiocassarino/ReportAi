package com.claude.reportAi.service;

import com.claude.reportAi.dto.WebSearchResult;

import java.util.List;

public interface WebSearchService {

    List<WebSearchResult> search(String query);
}
