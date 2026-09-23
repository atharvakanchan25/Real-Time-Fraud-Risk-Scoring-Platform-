package com.fraudplatform.enrichment.reputation;

import com.fraudplatform.enrichment.config.EnrichmentProperties;
import com.fraudplatform.enrichment.config.RedisGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Redis hash cache for device/IP reputation flags.
 *
 * Key schema:
 *   rep:device:{deviceId}  →  hash field "flags" = comma-separated flag list
 *   rep:ip:{ipAddress}     →  hash field "flags" = comma-separated flag list
 *
 * Cache miss → call ReputationStub → write back with TTL.
 */
@Component
@RequiredArgsConstructor
public class ReputationEnrichment {

    private static final String DEVICE_PREFIX = "rep:device:";
    private static final String IP_PREFIX     = "rep:ip:";
    private static final String FLAGS_FIELD   = "flags";

    private final StringRedisTemplate enrichmentRedisTemplate;
    private final EnrichmentProperties enrichmentProperties;
    private final ReputationStub reputationStub;
    private final RedisGuard redisGuard;

    public Set<String> deviceFlags(String deviceId) {
        return redisGuard.call(() -> lookupOrCache(
                DEVICE_PREFIX + deviceId,
                reputationStub.deviceFlags(deviceId)), Set.of());
    }

    public Set<String> ipFlags(String ipAddress) {
        return redisGuard.call(() -> lookupOrCache(
                IP_PREFIX + ipAddress,
                reputationStub.ipFlags(ipAddress)), Set.of());
    }

    private Set<String> lookupOrCache(String key, Set<String> freshFlags) {
        var ops = enrichmentRedisTemplate.opsForHash();
        String cached = (String) ops.get(key, FLAGS_FIELD);
        if (cached != null) {
            return cached.isEmpty() ? Set.of()
                    : Arrays.stream(cached.split(",")).collect(Collectors.toSet());
        }
        // Cache miss — write stub result back with TTL
        String serialised = String.join(",", freshFlags);
        ops.put(key, FLAGS_FIELD, serialised);
        enrichmentRedisTemplate.expire(key, Duration.ofSeconds(enrichmentProperties.reputationTtlSeconds()));
        return freshFlags;
    }
}
