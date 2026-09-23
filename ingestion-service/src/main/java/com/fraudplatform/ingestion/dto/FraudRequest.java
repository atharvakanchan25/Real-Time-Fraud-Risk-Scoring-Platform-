package com.fraudplatform.ingestion.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

@Data
public class FraudRequest {

    @NotBlank
    private String userId;

    @NotNull
    @DecimalMin("0.01")
    private BigDecimal amount;

    @NotBlank
    private String merchantId;

    @NotBlank
    private String deviceId;

    @NotBlank
    private String ipAddress;

    @NotNull
    private Instant timestamp;

    @NotBlank
    private String cardLast4;

    /** ISO-3166 alpha-2 country code of the card issuer, e.g. "US" */
    @NotBlank
    private String cardCountry;
}
