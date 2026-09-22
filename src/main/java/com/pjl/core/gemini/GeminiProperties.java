package com.pjl.core.gemini;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gemini.api")
public record GeminiProperties(
        String baseUrl,
        String key,
        String model
) {
}
