package com.fraudplatform.ingestion.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class FraudResponse {
    private String transactionId;
    private String decision;       // ALLOW | REVIEW | BLOCK
    private int riskScore;
    private List<String> rulesTriggered;
}
