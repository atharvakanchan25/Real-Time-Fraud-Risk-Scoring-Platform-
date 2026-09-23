package com.fraudplatform.enrichment;

import com.fraudplatform.enrichment.facade.EnrichmentFacade;
import com.fraudplatform.enrichment.model.EnrichedFeatures;
import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class EnrichmentFacadeIT {

    // Minimal Spring Boot app context for the library under test
    @SpringBootApplication(scanBasePackages = "com.fraudplatform.enrichment")
    static class TestApp {}

    @Container
    @ServiceConnection
    static RedisContainer redis = new RedisContainer(DockerImageName.parse("redis:7-alpine"));

    @Autowired EnrichmentFacade facade;

    @Test
    void velocityCountIncrementsPerUser() {
        String userId = "u-" + System.nanoTime();

        EnrichedFeatures f1 = facade.enrich(userId, "d-1", "10.0.0.1");
        EnrichedFeatures f2 = facade.enrich(userId, "d-1", "10.0.0.1");
        EnrichedFeatures f3 = facade.enrich(userId, "d-1", "10.0.0.1");

        assertThat(f1.velocityCount()).isEqualTo(1);
        assertThat(f2.velocityCount()).isEqualTo(2);
        assertThat(f3.velocityCount()).isEqualTo(3);
    }

    @Test
    void differentUsers_haveIndependentCounters() {
        String u1 = "ua-" + System.nanoTime();
        String u2 = "ub-" + System.nanoTime();

        facade.enrich(u1, "d-1", "10.0.0.1");
        facade.enrich(u1, "d-1", "10.0.0.1");
        EnrichedFeatures u2Features = facade.enrich(u2, "d-1", "10.0.0.1");

        assertThat(u2Features.velocityCount()).isEqualTo(1);
    }

    @Test
    void knownBadDevice_returnsRiskFlags() {
        EnrichedFeatures f = facade.enrich("u-1", "EMU-abc123", "10.0.0.1");
        assertThat(f.deviceRiskFlags()).contains("EMULATOR");
    }

    @Test
    void torExitIp_returnsRiskFlags() {
        EnrichedFeatures f = facade.enrich("u-1", "d-clean", "185.220.0.1");
        assertThat(f.ipRiskFlags()).contains("TOR_EXIT");
    }

    @Test
    void cleanDevice_returnsEmptyFlags() {
        EnrichedFeatures f = facade.enrich("u-1", "d-clean-device", "10.0.0.1");
        assertThat(f.deviceRiskFlags()).isEmpty();
        assertThat(f.ipRiskFlags()).isEmpty();
    }

    @Test
    void reputationResult_isCachedInRedis() {
        String deviceId = "EMU-cached-" + System.nanoTime();
        // First call — cache miss, writes to Redis
        EnrichedFeatures first  = facade.enrich("u-cache", deviceId, "10.0.0.1");
        // Second call — should hit Redis cache, same result
        EnrichedFeatures second = facade.enrich("u-cache", deviceId, "10.0.0.1");

        assertThat(first.deviceRiskFlags()).isEqualTo(second.deviceRiskFlags());
        assertThat(second.deviceRiskFlags()).contains("EMULATOR");
    }
}
