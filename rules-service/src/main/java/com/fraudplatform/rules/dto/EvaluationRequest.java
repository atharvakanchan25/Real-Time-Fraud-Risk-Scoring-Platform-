package com.fraudplatform.rules.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

@Data
public class EvaluationRequest {
    @NotBlank public String userId;
    @NotNull  public BigDecimal amount;
    public String merchantId;
    public String deviceId;
    public String ipAddress;
    public String cardCountry;

    // Enrichment — defaults to neutral if caller omits them
    public long              velocityCount   = -1;
    public Set<String>       deviceRiskFlags = Set.of();
    public Set<String>       ipRiskFlags     = Set.of();
    public Map<String, Object> features      = Map.of();
}
