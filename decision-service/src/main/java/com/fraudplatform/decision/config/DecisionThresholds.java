package com.fraudplatform.decision.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "decision.thresholds")
public record DecisionThresholds(int approveBelow, int blockAbove) {
    public DecisionThresholds {
        if (approveBelow <= 0) approveBelow = 30;
        if (blockAbove  <= 0) blockAbove  = 70;
    }
}
