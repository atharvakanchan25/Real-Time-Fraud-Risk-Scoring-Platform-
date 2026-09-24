package com.fraudplatform.rules.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class EvaluationResponse {

    @Data
    @Builder
    public static class TriggeredRule {
        private Long   ruleId;
        private String name;
        private int    weight;
        private String expression;
    }

    private List<TriggeredRule> triggeredRules;
    private int    totalWeight;
    private String decision;   // ALLOW | REVIEW | BLOCK
}
