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
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Executes a Redis call on a bounded thread pool, enforcing the TimeLimiter
 * and CircuitBreaker. Returns {@code fallback} on any timeout, circuit-open, or
 * exception — never propagates to the caller.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisGuard {

    private final TimeLimiter enrichmentTimeLimiter;
    private final CircuitBreaker enrichmentCircuitBreaker;

    // Bounded pool: max 20 daemon threads — prevents unbounded resource allocation
    // and allows clean JVM shutdown without hanging on pending Redis calls.
    private final ExecutorService executor = Executors.newFixedThreadPool(20, new ThreadFactory() {
        private final AtomicInteger count = new AtomicInteger(0);
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "redis-guard-" + count.incrementAndGet());
            t.setDaemon(true);   // daemon: JVM can exit even if threads are waiting
            return t;
        }
    });

    public <T> T call(Callable<T> redisCall, T fallback) {
        try {
            Callable<T> decorated = CircuitBreaker.decorateCallable(
                    enrichmentCircuitBreaker,
                    TimeLimiter.decorateFutureSupplier(
                            enrichmentTimeLimiter,
                            () -> (Future<T>) executor.submit(redisCall)));
            return decorated.call();
        } catch (Exception e) {
            // Log with full stack trace so Redis errors are diagnosable in production
            log.warn("Redis enrichment fallback triggered: {}", e.getMessage(), e);
            return fallback;
        }
    }
}
