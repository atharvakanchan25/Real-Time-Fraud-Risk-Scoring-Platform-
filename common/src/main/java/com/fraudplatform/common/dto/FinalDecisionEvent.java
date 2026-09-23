package com.fraudplatform.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FinalDecisionEvent {
    private String transactionId;
    private String userId;
    private String deviceId;
    /** APPROVE | REVIEW | BLOCK */
    private String outcome;
    private double fraudScore;
    private String ruleTriggered;
    private Instant decidedAt;
}
