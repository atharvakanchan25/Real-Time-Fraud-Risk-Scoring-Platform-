package com.fraudplatform.ingestion;

import com.fraudplatform.ingestion.dto.FraudRequest;
import com.fraudplatform.ingestion.dto.FraudResponse;
import com.fraudplatform.ingestion.entity.CaseDecision;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class TransactionControllerIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired
    TestRestTemplate rest;

    // ── helpers ──────────────────────────────────────────────────────────────

    private FraudRequest baseRequest() {
        FraudRequest r = new FraudRequest();
        r.setUserId("user-" + System.nanoTime());
        r.setAmount(new BigDecimal("100.00"));
        r.setMerchantId("merchant-1");
        r.setDeviceId("device-1");
        r.setIpAddress("10.0.0.1");   // US stub
        r.setTimestamp(Instant.now());
        r.setCardLast4("1234");
        r.setCardCountry("US");
        return r;
    }

    private FraudResponse post(FraudRequest req) {
        ResponseEntity<FraudResponse> resp = rest.postForEntity("/transactions", req, FraudResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return resp.getBody();
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    void cleanTransaction_isAllowed() {
        FraudResponse resp = post(baseRequest());
        assertThat(resp.getDecision()).isEqualTo("ALLOW");
        assertThat(resp.getRiskScore()).isLessThan(30);
        assertThat(resp.getRulesTriggered()).isEmpty();
    }

    @Test
    void highAmount_triggersRule_andReview() {
        FraudRequest req = baseRequest();
        req.setAmount(new BigDecimal("15000.00"));

        FraudResponse resp = post(req);
        assertThat(resp.getRulesTriggered()).contains("HIGH_AMOUNT");
        assertThat(resp.getRiskScore()).isGreaterThanOrEqualTo(30);
    }

    @Test
    void velocityBreached_triggersRule() {
        String userId = "velocity-user-" + System.nanoTime();
        // Submit 5 transactions to hit the threshold (default velocityMaxCount=5)
        for (int i = 0; i < 5; i++) {
            FraudRequest req = baseRequest();
            req.setUserId(userId);
            post(req);
        }
        // 6th should trigger HIGH_VELOCITY
        FraudRequest req = baseRequest();
        req.setUserId(userId);
        FraudResponse resp = post(req);
        assertThat(resp.getRulesTriggered()).contains("HIGH_VELOCITY");
    }

    @Test
    void geoMismatch_triggersRule() {
        FraudRequest req = baseRequest();
        req.setIpAddress("185.0.0.1");  // RU stub
        req.setCardCountry("US");

        FraudResponse resp = post(req);
        assertThat(resp.getRulesTriggered()).contains("GEO_MISMATCH");
    }

    @Test
    void allThreeRules_scoreIsBlock() {
        String userId = "block-user-" + System.nanoTime();
        // Pre-fill velocity
        for (int i = 0; i < 5; i++) {
            FraudRequest r = baseRequest();
            r.setUserId(userId);
            post(r);
        }
        FraudRequest req = baseRequest();
        req.setUserId(userId);
        req.setAmount(new BigDecimal("20000.00"));
        req.setIpAddress("185.0.0.1");  // RU
        req.setCardCountry("US");

        FraudResponse resp = post(req);
        assertThat(resp.getDecision()).isEqualTo("BLOCK");
        assertThat(resp.getRiskScore()).isGreaterThan(70);
    }

    @Test
    void getCases_returnsReviewDecisions() {
        // Create a REVIEW-worthy transaction (one rule hit → score 35)
        FraudRequest req = baseRequest();
        req.setAmount(new BigDecimal("15000.00"));
        FraudResponse created = post(req);
        assertThat(created.getDecision()).isEqualTo("REVIEW");

        ResponseEntity<List<CaseDecision>> resp = rest.exchange(
                "/cases?status=REVIEW", HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).anyMatch(c -> c.getTransactionId().equals(created.getTransactionId()));
    }
}
