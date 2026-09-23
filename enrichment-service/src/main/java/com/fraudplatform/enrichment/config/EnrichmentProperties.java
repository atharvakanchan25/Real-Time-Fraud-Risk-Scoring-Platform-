package com.fraudplatform.enrichment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "enrichment")
public record EnrichmentProperties(
        long velocityWindowSeconds,
        long reputationTtlSeconds,
        long redisTimeoutMs
) {
    public EnrichmentProperties {
        if (velocityWindowSeconds <= 0) velocityWindowSeconds = 300;
        if (reputationTtlSeconds  <= 0) reputationTtlSeconds  = 3600;
        if (redisTimeoutMs        <= 0) redisTimeoutMs        = 5;
    }
}
