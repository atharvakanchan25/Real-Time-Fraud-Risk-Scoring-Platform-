package com.fraudplatform.scoring.client;

import com.fraudplatform.enrichment.model.EnrichedFeatures;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Calls rules-service POST /rules/evaluate over HTTP.
 * The request/response shapes are inlined here as Maps to avoid a compile-time
 * dependency on the rules-service module (it is a separate deployable service).
 * Wrapped in a Resilience4j circuit breaker named "rules-service".
 */
@Slf4j
@Component
public class RulesServiceClient {

    private final RestClient restClient;

    public RulesServiceClient(@Value("${rules.service.url:http://localhost:8083}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    @CircuitBreaker(name = "rules-service", fallbackMethod = "fallback")
    public Optional<Map<String, Object>> evaluate(String userId, BigDecimal amount,
                                                   String merchantId, String deviceId,
                                                   String ipAddress, String cardCountry,
                                                   EnrichedFeatures features) {
        Map<String, Object> req = Map.of(
                "userId",          userId,
                "amount",          amount,
                "merchantId",      merchantId,
                "deviceId",        deviceId,
                "ipAddress",       ipAddress,
                "cardCountry",     cardCountry,
                "velocityCount",   features.velocityCount(),
                "deviceRiskFlags", features.deviceRiskFlags(),
                "ipRiskFlags",     features.ipRiskFlags()
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> response = restClient.post()
                .uri("/rules/evaluate")
                .body(req)
                .retrieve()
                .body(Map.class);

        return Optional.ofNullable(response);
    }

    @SuppressWarnings("unused")
    private Optional<Map<String, Object>> fallback(String userId, BigDecimal amount,
                                                    String merchantId, String deviceId,
                                                    String ipAddress, String cardCountry,
                                                    EnrichedFeatures features, Throwable t) {
        log.warn("rules-service circuit open or failed ({}): falling back to embedded engine", t.getMessage(), t);
        return Optional.empty();
    }
}
