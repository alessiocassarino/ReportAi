package com.claude.reportAi.configuration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(TavilyProperties.class)
public class TavilyConfig {

    @Bean(name = "tavilyRestClient")
    public RestClient tavilyRestClient(RestClient.Builder builder, TavilyProperties properties) {
        return builder
                .baseUrl(properties.getBaseUrl())
                .build();
    }
}