package com.fraudplatform.scoring.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/**
 * Calls the ML inference service to get a fraud probability score.
 * Wrapped in a Resilience4j circuit breaker named "ml-inference".
 * Falls back to {@link Optional#empty()} so the caller can degrade to rules-only scoring.
 */
@Slf4j
@Component
public class MlInferenceClient {

    private final RestClient restClient;

    public MlInferenceClient(@Value("${ml.inference.url:http://localhost:8088}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    @CircuitBreaker(name = "ml-inference", fallbackMethod = "fallback")
    public Optional<Double> infer(String userId, BigDecimal amount, String deviceId, String ipAddress) {
        @SuppressWarnings("unchecked")
        Map<String, Object> response = restClient.post()
                .uri("/infer")
                .body(Map.of(
                        "userId",    userId,
                        "amount",    amount,
                        "deviceId",  deviceId,
                        "ipAddress", ipAddress))
                .retrieve()
                .body(Map.class);

        if (response == null) return Optional.empty();
        Object prob = response.get("fraudProbability");
        if (prob instanceof Number n) return Optional.of(n.doubleValue());
        return Optional.empty();
    }

    @SuppressWarnings("unused")
    private Optional<Double> fallback(String userId, BigDecimal amount,
                                      String deviceId, String ipAddress, Throwable t) {
        log.warn("ML inference circuit open or failed ({}): degrading to rules-only", t.getMessage());
        return Optional.empty();
    }
}
