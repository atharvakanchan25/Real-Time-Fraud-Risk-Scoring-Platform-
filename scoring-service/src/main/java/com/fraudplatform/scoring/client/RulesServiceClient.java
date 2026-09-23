package com.fraudplatform.scoring.client;

import com.fraudplatform.enrichment.model.EnrichedFeatures;
import com.fraudplatform.rules.dto.EvaluationRequest;
import com.fraudplatform.rules.dto.EvaluationResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Calls rules-service POST /rules/evaluate.
 * Wrapped in a Resilience4j circuit breaker named "rules-service".
 * Falls back to {@link Optional#empty()} so scoring-service can fall back to
 * its embedded FraudRulesEngine.
 */
@Slf4j
@Component
public class RulesServiceClient {

    private final RestClient restClient;

    public RulesServiceClient(@Value("${rules.service.url:http://localhost:8083}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    @CircuitBreaker(name = "rules-service", fallbackMethod = "fallback")
    public Optional<EvaluationResponse> evaluate(String userId, BigDecimal amount,
                                                  String merchantId, String deviceId,
                                                  String ipAddress, String cardCountry,
                                                  EnrichedFeatures features) {
        EvaluationRequest req = new EvaluationRequest();
        req.setUserId(userId);
        req.setAmount(amount);
        req.setMerchantId(merchantId);
        req.setDeviceId(deviceId);
        req.setIpAddress(ipAddress);
        req.setCardCountry(cardCountry);
        req.setVelocityCount(features.velocityCount());
        req.setDeviceRiskFlags(features.deviceRiskFlags());
        req.setIpRiskFlags(features.ipRiskFlags());

        EvaluationResponse response = restClient.post()
                .uri("/rules/evaluate")
                .body(req)
                .retrieve()
                .body(EvaluationResponse.class);

        return Optional.ofNullable(response);
    }

    @SuppressWarnings("unused")
    private Optional<EvaluationResponse> fallback(String userId, BigDecimal amount,
                                                   String merchantId, String deviceId,
                                                   String ipAddress, String cardCountry,
                                                   EnrichedFeatures features, Throwable t) {
        log.warn("rules-service circuit open or failed ({}): falling back to embedded engine", t.getMessage());
        return Optional.empty();
    }
}
