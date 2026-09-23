package com.fraudplatform.enrichment.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(EnrichmentProperties.class)
public class EnrichmentConfig {

    @Bean
    public StringRedisTemplate enrichmentRedisTemplate(RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }

    @Bean
    public TimeLimiter enrichmentTimeLimiter(EnrichmentProperties props) {
        return TimeLimiter.of("enrichment-redis",
                TimeLimiterConfig.custom()
                        .timeoutDuration(Duration.ofMillis(props.redisTimeoutMs()))
                        .build());
    }

    @Bean
    public CircuitBreaker enrichmentCircuitBreaker(CircuitBreakerRegistry circuitBreakerRegistry) {
        return circuitBreakerRegistry.circuitBreaker("enrichment-redis",
                CircuitBreakerConfig.custom()
                        .failureRateThreshold(50)
                        .waitDurationInOpenState(Duration.ofSeconds(10))
                        .slidingWindowSize(20)
                        .build());
    }
}
