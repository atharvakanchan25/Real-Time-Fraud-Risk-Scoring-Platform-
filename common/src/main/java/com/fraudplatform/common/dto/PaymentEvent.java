package com.fraudplatform.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentEvent {
    /** Idempotency key assigned by ingestion-service (UUID). */
    private String idempotencyKey;
    private String userId;
    private BigDecimal amount;
    private String merchantId;
    private String deviceId;
    private String ipAddress;
    private Instant timestamp;
    private String cardLast4;
    private String cardCountry;
}
