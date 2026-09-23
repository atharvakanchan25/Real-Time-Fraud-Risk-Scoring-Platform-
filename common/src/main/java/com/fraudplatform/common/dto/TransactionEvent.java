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
public class TransactionEvent {
    private String transactionId;
    private String accountId;
    private String merchantId;
    private BigDecimal amount;
    private String currency;
    private String channel;
    private Instant timestamp;
    private String ipAddress;
    private String deviceId;
}
