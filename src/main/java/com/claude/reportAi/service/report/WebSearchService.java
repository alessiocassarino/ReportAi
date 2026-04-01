package com.claude.reportAi.service.report;

import java.util.List;

public interface WebSearchService {

    List<SearchResult> search(String query);

    record SearchResult(String title, String url, String content) {}
}
