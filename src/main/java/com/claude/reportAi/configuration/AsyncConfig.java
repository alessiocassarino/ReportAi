package com.claude.reportAi.configuration;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "contractAnalysisExecutor")
    public Executor contractAnalysisExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("contract-analysis-");
        executor.initialize();
        return executor;
    }

    @Bean(name = "documentUploadExecutor")
    public Executor documentUploadExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("doc-upload-");
        executor.initialize();
        return executor;
    }

    @Bean(name = "reportGenerationExecutor")
    public Executor reportGenerationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("report-gen-");
        executor.initialize();
        return executor;
    }

    @Bean(name = "priceComparisonExecutor")
    public Executor priceComparisonExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(5);
        executor.setThreadNamePrefix("price-comparison-");
        executor.initialize();
        return executor;
    }

    /**
     * Aumenta il read timeout a 15 minuti per supportare le chiamate lunghe
     * alla Skills API di Anthropic (generazione DOCX richiede esecuzione
     * di codice server-side che può durare diversi minuti).
     */
    @Bean
    public RestClientCustomizer anthropicLongTimeoutCustomizer() {
        return builder -> {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(30_000);      // 30 sec connect
            factory.setReadTimeout(15 * 60 * 1000); // 15 min read
            builder.requestFactory(factory);
        };
    }
}
