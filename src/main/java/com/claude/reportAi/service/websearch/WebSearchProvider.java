package com.claude.reportAi.service.websearch;

import java.util.List;

public interface WebSearchProvider {
    List<String> search(String query);
}
