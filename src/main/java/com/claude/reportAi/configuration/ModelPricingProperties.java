package com.claude.reportAi.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

@ConfigurationProperties(prefix = "app.pricing")
public record ModelPricingProperties(Map<String, ModelPrice> models) {

    public record ModelPrice(double input, double output) {}

    public ModelPrice priceFor(String modelId) {
        if (models == null || modelId == null) return null;
        return models.get(modelId);
    }
}
