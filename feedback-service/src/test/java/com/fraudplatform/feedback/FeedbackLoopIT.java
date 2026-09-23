package com.fraudplatform.feedback;

import com.fraudplatform.common.dto.FeedbackEvent;
import com.fraudplatform.feedback.service.ReputationUpdateService;
import com.redis.testcontainers.RedisContainer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Testcontainers
@DirtiesContext
@EmbeddedKafka(
        partitions = 1,
        topics = "feedback-events",
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
@TestPropertySource(properties = {
        "spring.kafka.consumer.group-id=feedback-test-group",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.consumer.feedback-topic=feedback-events",
        "feedback.risk-bump=0.5",
        "feedback.reputation-ttl-seconds=3600"
})
class FeedbackLoopIT {

    @SpringBootApplication(scanBasePackages = "com.fraudplatform.feedback")
    static class TestApp {
        @Configuration
        static class KafkaProducerConfig {
            @Bean
            public ProducerFactory<String, FeedbackEvent> feedbackProducerFactory(
                    org.springframework.kafka.test.EmbeddedKafkaBroker broker) {
                return new DefaultKafkaProducerFactory<>(Map.of(
                        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString(),
                        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class
                ));
            }

            @Bean
            public KafkaTemplate<String, FeedbackEvent> feedbackKafkaTemplate(
                    ProducerFactory<String, FeedbackEvent> pf) {
                return new KafkaTemplate<>(pf);
            }
        }
    }

    @Container
    @ServiceConnection
    static RedisContainer redis = new RedisContainer(DockerImageName.parse("redis:7-alpine"));

    @Autowired
    KafkaTemplate<String, FeedbackEvent> kafkaTemplate;

    @Autowired
    StringRedisTemplate feedbackRedisTemplate;

    @Test
    void confirmedFraud_updatesRedisReputation_andEnrichmentReturnsHighRisk() {
        String userId   = "user-" + System.nanoTime();
        String deviceId = "device-" + System.nanoTime();

        // 1. Publish a CONFIRMED_FRAUD feedback event (simulates analyst verdict)
        FeedbackEvent event = FeedbackEvent.builder()
                .caseId("case-1")
                .transactionId("tx-" + System.nanoTime())
                .userId(userId)
                .deviceId(deviceId)
                .verdict("CONFIRMED_FRAUD")
                .analyst("analyst1")
                .decidedAt(Instant.now())
                .build();

        kafkaTemplate.send("feedback-events", userId, event);

        // 2. Wait up to 5 s for the consumer to process (one polling cycle)
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            String userKey   = ReputationUpdateService.USER_PREFIX + userId;
            String deviceKey = ReputationUpdateService.DEVICE_PREFIX + deviceId;

            String userFlags   = (String) feedbackRedisTemplate.opsForHash().get(userKey,   ReputationUpdateService.FLAGS_FIELD);
            String deviceFlags = (String) feedbackRedisTemplate.opsForHash().get(deviceKey, ReputationUpdateService.FLAGS_FIELD);

            assertThat(userFlags).as("user reputation key should contain HIGH_RISK")
                    .contains(ReputationUpdateService.HIGH_RISK_FLAG);
            assertThat(deviceFlags).as("device reputation key should contain HIGH_RISK")
                    .contains(ReputationUpdateService.HIGH_RISK_FLAG);
        });

        // 3. Verify the next transaction from the same device would be enriched with HIGH_RISK
        //    by reading the rep:device key directly — same key the enrichment-service reads
        String deviceKey = ReputationUpdateService.DEVICE_PREFIX + deviceId;
        String flags = (String) feedbackRedisTemplate.opsForHash()
                .get(deviceKey, ReputationUpdateService.FLAGS_FIELD);

        assertThat(flags).contains("HIGH_RISK");
    }

    @Test
    void falsePosivite_doesNotUpdateRedis() {
        String userId   = "user-fp-" + System.nanoTime();
        String deviceId = "device-fp-" + System.nanoTime();

        FeedbackEvent event = FeedbackEvent.builder()
                .caseId("case-2")
                .transactionId("tx-fp-" + System.nanoTime())
                .userId(userId)
                .deviceId(deviceId)
                .verdict("FALSE_POSITIVE")
                .analyst("analyst1")
                .decidedAt(Instant.now())
                .build();

        kafkaTemplate.send("feedback-events", userId, event);

        // Give the consumer time to process, then assert nothing was written
        await().during(2, TimeUnit.SECONDS).atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
            String userFlags = (String) feedbackRedisTemplate.opsForHash()
                    .get(ReputationUpdateService.USER_PREFIX + userId, ReputationUpdateService.FLAGS_FIELD);
            assertThat(userFlags).as("FALSE_POSITIVE should not write any reputation flags").isNull();
        });
    }
}
