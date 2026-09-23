package com.fraudplatform.enrichment.velocity;

import com.fraudplatform.enrichment.config.EnrichmentProperties;
import com.fraudplatform.enrichment.config.RedisGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Sliding-window velocity counter backed by a Redis ZSET.
 *
 * Key  : velocity:{userId}
 * Score: epoch-millis of each transaction
 * On each call:
 *   1. ZADD the current timestamp (score = member = millis string)
 *   2. ZREMRANGEBYSCORE to trim entries older than the window
 *   3. ZCARD to get the current count
 *   4. EXPIRE to auto-clean the key after the window passes
 */
@Component
@RequiredArgsConstructor
public class VelocityEnrichment {

    private static final String KEY_PREFIX = "velocity:";

    private final StringRedisTemplate enrichmentRedisTemplate;
    private final EnrichmentProperties enrichmentProperties;
    private final RedisGuard redisGuard;

    /**
     * Records this transaction and returns the sliding-window count for {@code userId}.
     * Returns -1 if Redis is unavailable or times out.
     */
    public long recordAndCount(String userId) {
        return redisGuard.call(() -> {
            String key = KEY_PREFIX + userId;
            long nowMillis = Instant.now().toEpochMilli();
            long windowMillis = enrichmentProperties.velocityWindowSeconds() * 1_000;
            long cutoff = nowMillis - windowMillis;

            var ops = enrichmentRedisTemplate.opsForZSet();
            // Add current event (score = member = timestamp string for uniqueness)
            ops.add(key, String.valueOf(nowMillis), nowMillis);
            // Trim entries outside the window
            ops.removeRangeByScore(key, 0, cutoff);
            // Count remaining entries in window
            Long count = ops.zCard(key);
            // TTL slightly longer than window so key self-cleans
            enrichmentRedisTemplate.expire(key,
                    java.time.Duration.ofSeconds(enrichmentProperties.velocityWindowSeconds() + 10));
            return count == null ? 1L : count;
        }, -1L);
    }
}
