package com.fraudplatform.ingestion.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TransactionResponse {
    private String idempotencyKey;
    private String status;   // ACCEPTED
}
