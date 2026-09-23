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
public class DecisionEvent {
    private String transactionId;
    private String decision;   // APPROVE | DECLINE | REVIEW
    private double fraudScore;
    private String ruleTriggered;
    private Instant decidedAt;
}
