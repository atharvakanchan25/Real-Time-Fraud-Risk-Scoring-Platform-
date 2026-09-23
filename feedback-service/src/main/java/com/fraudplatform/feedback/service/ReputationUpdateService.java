package com.fraudplatform.feedback.service;

import com.fraudplatform.feedback.config.FeedbackProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Writes HIGH_RISK reputation flags into Redis using the same key schema
 * as enrichment-service so the next transaction from this user/device
 * is immediately enriched with elevated risk signals.
 *
 * Key schema (mirrors ReputationEnrichment):
 *   rep:device:{deviceId}  → hash field "flags" = comma-separated flags
 *   rep:user:{userId}      → hash field "flags" = comma-separated flags
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReputationUpdateService {

    public static final String DEVICE_PREFIX  = "rep:device:";
    public static final String USER_PREFIX    = "rep:user:";
    public static final String FLAGS_FIELD    = "flags";
    public static final String HIGH_RISK_FLAG = "HIGH_RISK";

    private final StringRedisTemplate feedbackRedisTemplate;
    private final FeedbackProperties feedbackProperties;

    public void markHighRisk(String userId, String deviceId) {
        Duration ttl = Duration.ofSeconds(feedbackProperties.reputationTtlSeconds());

        if (userId != null && !userId.isBlank()) {
            applyFlag(USER_PREFIX + userId, ttl);
            log.info("Reputation updated: userId={} flag={}", userId, HIGH_RISK_FLAG);
        }
        if (deviceId != null && !deviceId.isBlank()) {
            applyFlag(DEVICE_PREFIX + deviceId, ttl);
            log.info("Reputation updated: deviceId={} flag={}", deviceId, HIGH_RISK_FLAG);
        }
    }

    private void applyFlag(String key, Duration ttl) {
        var ops = feedbackRedisTemplate.opsForHash();
        String existing = (String) ops.get(key, FLAGS_FIELD);
        String updated = (existing == null || existing.isBlank())
                ? HIGH_RISK_FLAG
                : (existing.contains(HIGH_RISK_FLAG) ? existing : existing + "," + HIGH_RISK_FLAG);
        ops.put(key, FLAGS_FIELD, updated);
        feedbackRedisTemplate.expire(key, ttl);
    }
}
