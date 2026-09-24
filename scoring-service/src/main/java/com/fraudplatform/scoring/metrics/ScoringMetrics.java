package com.fraudplatform.scoring.metrics;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class ScoringMetrics {

    private final MeterRegistry meterRegistry;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    private Timer   scoringLatencyTimer;
    private Counter dlqCounter;

    @PostConstruct
    public void init() {
        scoringLatencyTimer = Timer.builder("fraud.scoring.latency")
                .description("End-to-end scoring latency per transaction")
                .publishPercentiles(0.50, 0.95, 0.99)
                .register(meterRegistry);

        dlqCounter = Counter.builder("fraud.scoring.dlq.count")
                .description("Number of messages routed to the dead-letter topic")
                .register(meterRegistry);

        // Register CB state-transition event listeners for each named breaker
        for (String name : new String[]{"ml-inference", "rules-service", "enrichment-redis"}) {
            circuitBreakerRegistry.find(name).ifPresent(cb -> registerCbMetrics(cb, name));
            // Also listen for future registrations (breakers created lazily by annotations)
            circuitBreakerRegistry.getEventPublisher()
                    .onEntryAdded(e -> {
                        if (e.getAddedEntry().getName().equals(name)) {
                            registerCbMetrics(e.getAddedEntry(), name);
                        }
                    });
        }
    }

    private void registerCbMetrics(CircuitBreaker cb, String name) {
        Counter openCounter = Counter.builder("fraud.circuit_breaker.state_transition")
                .tag("name", name).tag("transition", "to_open")
                .description("Circuit breaker transitions to OPEN")
                .register(meterRegistry);
        Counter closedCounter = Counter.builder("fraud.circuit_breaker.state_transition")
                .tag("name", name).tag("transition", "to_closed")
                .description("Circuit breaker transitions to CLOSED")
                .register(meterRegistry);
        Counter halfOpenCounter = Counter.builder("fraud.circuit_breaker.state_transition")
                .tag("name", name).tag("transition", "to_half_open")
                .description("Circuit breaker transitions to HALF_OPEN")
                .register(meterRegistry);

        cb.getEventPublisher()
                .onStateTransition(e -> {
                    log.info("CircuitBreaker '{}' transitioned: {} → {}",
                            name, e.getStateTransition().getFromState(), e.getStateTransition().getToState());
                    switch (e.getStateTransition().getToState()) {
                        case OPEN      -> openCounter.increment();
                        case CLOSED    -> closedCounter.increment();
                        case HALF_OPEN -> halfOpenCounter.increment();
                        default        -> log.debug("CircuitBreaker '{}' transitioned to untracked state", name);
                    }
                });
    }

    public void recordScoringLatency(long nanos) {
        scoringLatencyTimer.record(nanos, TimeUnit.NANOSECONDS);
    }

    public void incrementDlqCount() {
        dlqCounter.increment();
    }
}
