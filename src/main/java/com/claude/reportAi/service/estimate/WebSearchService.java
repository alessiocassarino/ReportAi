package com.claude.reportAi.service.estimate;

import java.util.List;

public interface WebSearchService {

    List<SearchResult> search(String query);

    record SearchResult(String title, String url, String content) {}
}
