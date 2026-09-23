package com.fraudplatform.feedback.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "feedback")
public record FeedbackProperties(double riskBump, long reputationTtlSeconds) {
    public FeedbackProperties {
        if (riskBump <= 0)               riskBump = 0.5;
        if (reputationTtlSeconds <= 0)   reputationTtlSeconds = 86400;
    }
}
