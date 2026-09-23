package com.fraudplatform.scoring;

import com.fraudplatform.common.dto.PaymentEvent;
import com.fraudplatform.scoring.entity.CaseDecision;
import com.fraudplatform.scoring.repository.CaseDecisionRepository;
import com.fraudplatform.scoring.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Testcontainers
class OrderingGuaranteeIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @DynamicPropertySource
    static void kafkaProps(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired KafkaTemplate<String, PaymentEvent> kafkaTemplate;
    @Autowired TransactionRepository transactionRepository;
    @Autowired CaseDecisionRepository caseDecisionRepository;

    @Value("${kafka.topics.payment-events}") String paymentEventsTopic;

    private PaymentEvent buildEvent(String userId, BigDecimal amount) {
        return PaymentEvent.builder()
                .idempotencyKey(UUID.randomUUID().toString())
                .userId(userId)
                .amount(amount)
                .merchantId("m-1")
                .deviceId("d-1")
                .ipAddress("10.0.0.1")
                .timestamp(Instant.now())
                .cardLast4("1234")
                .cardCountry("US")
                .build();
    }

    @Test
    void sameUserId_processedInOrder() {
        String userId = "order-test-" + System.nanoTime();
        int batchSize = 6;
        List<String> sentKeys = new ArrayList<>();

        // All events use the same userId as Kafka key → same partition → strict ordering
        for (int i = 0; i < batchSize; i++) {
            PaymentEvent event = buildEvent(userId, new BigDecimal("100.00"));
            sentKeys.add(event.getIdempotencyKey());
            kafkaTemplate.send(paymentEventsTopic, userId, event);
        }

        // Wait until all are persisted
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            List<CaseDecision> decisions = caseDecisionRepository.findAll().stream()
                    .filter(d -> d.getUserId().equals(userId))
                    .toList();
            assertThat(decisions).hasSize(batchSize);
        });

        // Verify every sent key has a corresponding persisted transaction
        List<String> persistedKeys = transactionRepository.findAll().stream()
                .filter(t -> t.getUserId().equals(userId))
                .map(t -> t.getIdempotencyKey())
                .toList();

        assertThat(persistedKeys).containsExactlyInAnyOrderElementsOf(sentKeys);

        // Ordering proof: created_at timestamps must be non-decreasing
        List<Instant> timestamps = transactionRepository.findAll().stream()
                .filter(t -> t.getUserId().equals(userId))
                .sorted((a, b) -> {
                    int idxA = sentKeys.indexOf(a.getIdempotencyKey());
                    int idxB = sentKeys.indexOf(b.getIdempotencyKey());
                    return Integer.compare(idxA, idxB);
                })
                .map(t -> t.getCreatedAt())
                .toList();

        for (int i = 1; i < timestamps.size(); i++) {
            assertThat(timestamps.get(i)).isAfterOrEqualTo(timestamps.get(i - 1));
        }
    }

    @Test
    void duplicateEvent_isIdempotent() {
        String userId = "idem-test-" + System.nanoTime();
        PaymentEvent event = buildEvent(userId, new BigDecimal("200.00"));

        // Send the same event twice
        kafkaTemplate.send(paymentEventsTopic, userId, event);
        kafkaTemplate.send(paymentEventsTopic, userId, event);

        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(transactionRepository.existsById(event.getIdempotencyKey())).isTrue()
        );

        // Only one row should exist despite two messages
        long count = transactionRepository.findAll().stream()
                .filter(t -> t.getIdempotencyKey().equals(event.getIdempotencyKey()))
                .count();
        assertThat(count).isEqualTo(1);
    }
}
