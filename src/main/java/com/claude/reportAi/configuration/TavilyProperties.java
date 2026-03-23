package com.claude.reportAi.configuration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.tavily")
public class TavilyProperties {

    private String apiKey = "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";
    private String baseUrl = "https://api.tavily.com";

    private boolean autoParameters = true;
    private String searchDepth = "basic";
    private String topic = "general";
    private int maxResults = 5;

    /**
     * Tavily accetta false / true / basic / advanced
     */
    private String includeAnswer = "basic";
}