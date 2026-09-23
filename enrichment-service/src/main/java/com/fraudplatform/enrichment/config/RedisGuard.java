package com.fraudplatform.enrichment.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.timelimiter.TimeLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Executes a Redis call on a virtual-thread executor, enforcing the TimeLimiter
 * and CircuitBreaker. Returns {@code fallback} on any timeout, circuit-open, or
 * exception — never propagates to the caller.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisGuard {

    private final TimeLimiter enrichmentTimeLimiter;
    private final CircuitBreaker enrichmentCircuitBreaker;

    // Bounded cached pool — virtual threads require Java 21+; cached threads are fine at this scale
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public <T> T call(Callable<T> redisCall, T fallback) {
        try {
            Callable<T> decorated = CircuitBreaker.decorateCallable(
                    enrichmentCircuitBreaker,
                    TimeLimiter.decorateFutureSupplier(
                            enrichmentTimeLimiter,
                            () -> (Future<T>) executor.submit(redisCall)));
            return decorated.call();
        } catch (Exception e) {
            log.debug("Redis enrichment fallback triggered: {}", e.getMessage());
            return fallback;
        }
    }
}
