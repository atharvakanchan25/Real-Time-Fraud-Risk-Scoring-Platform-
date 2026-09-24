package com.fraudplatform.rules;

import com.fraudplatform.rules.dto.CreateRuleRequest;
import com.fraudplatform.rules.dto.EvaluationRequest;
import com.fraudplatform.rules.dto.EvaluationResponse;
import com.fraudplatform.rules.entity.Rule;
import com.fraudplatform.rules.entity.RuleAuditLog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class RulesEngineIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    // TestRestTemplate with Basic auth credentials matching application.yml
    @Autowired
    TestRestTemplate rest;

    private TestRestTemplate authed() {
        return rest.withBasicAuth("analyst", "analyst");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Rule createRule(String name, String expression, int weight) {
        CreateRuleRequest req = new CreateRuleRequest();
        req.setName(name);
        req.setConditionExpression(expression);
        req.setWeight(weight);
        ResponseEntity<Rule> resp = authed().postForEntity("/rules", req, Rule.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return resp.getBody();
    }

    private EvaluationResponse evaluate(EvaluationRequest req) {
        ResponseEntity<EvaluationResponse> resp = authed()
                .postForEntity("/rules/evaluate", req, EvaluationResponse.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }

    private EvaluationRequest baseRequest() {
        EvaluationRequest req = new EvaluationRequest();
        req.setUserId("u-test");
        req.setAmount(new BigDecimal("100.00"));
        req.setMerchantId("m-1");
        req.setDeviceId("d-clean");
        req.setIpAddress("10.0.0.1");
        req.setCardCountry("US");
        req.setVelocityCount(1);
        req.setDeviceRiskFlags(Set.of());
        req.setIpRiskFlags(Set.of());
        return req;
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    void newRule_appliedImmediately_withoutRestart() {
        // 1. No rules yet — transaction should be ALLOW
        EvaluationResponse before = evaluate(baseRequest());
        assertThat(before.getDecision()).isEqualTo("ALLOW");
        assertThat(before.getTriggeredRules()).isEmpty();

        // 2. Add a brand-new rule via the API
        createRule("HIGH_AMOUNT_DYNAMIC", "amount > 50", 30);

        // 3. Evaluate the SAME transaction immediately — rule must fire, no restart
        EvaluationRequest req = baseRequest();
        req.setAmount(new BigDecimal("200.00"));
        EvaluationResponse after = evaluate(req);

        assertThat(after.getTriggeredRules()).hasSize(1);
        assertThat(after.getTriggeredRules().get(0).getName()).isEqualTo("HIGH_AMOUNT_DYNAMIC");
        assertThat(after.getDecision()).isEqualTo("REVIEW");
    }

    @Test
    void deactivatedRule_isNotEvaluated() {
        Rule rule = createRule("TEMP_RULE", "amount > 1", 25);

        // Confirm it fires
        EvaluationResponse active = evaluate(baseRequest());
        assertThat(active.getTriggeredRules().stream()
                .anyMatch(r -> r.getName().equals("TEMP_RULE"))).isTrue();

        // Deactivate via PATCH
        authed().patchForObject("/rules/" + rule.getId(),
                new org.springframework.http.HttpEntity<>(
                        java.util.Map.of("active", false),
                        jsonHeaders()),
                Rule.class);

        // Must not fire anymore
        EvaluationResponse inactive = evaluate(baseRequest());
        assertThat(inactive.getTriggeredRules().stream()
                .noneMatch(r -> r.getName().equals("TEMP_RULE"))).isTrue();
    }

    @Test
    void velocityRule_triggersOnHighCount() {
        createRule("VELOCITY_RULE", "velocityCount >= 5", 35);

        EvaluationRequest req = baseRequest();
        req.setVelocityCount(6);
        EvaluationResponse resp = evaluate(req);

        assertThat(resp.getTriggeredRules().stream()
                .anyMatch(r -> r.getName().equals("VELOCITY_RULE"))).isTrue();
    }

    @Test
    void deviceRiskFlag_triggersRule() {
        createRule("EMULATOR_RULE", "deviceRiskFlags.contains('EMULATOR')", 40);

        EvaluationRequest req = baseRequest();
        req.setDeviceRiskFlags(Set.of("EMULATOR"));
        EvaluationResponse resp = evaluate(req);

        assertThat(resp.getTriggeredRules().stream()
                .anyMatch(r -> r.getName().equals("EMULATOR_RULE"))).isTrue();
        assertThat(resp.getDecision()).isEqualTo("BLOCK");
    }

    @Test
    void auditLog_recordsCreateAndDeactivate() {
        Rule rule = createRule("AUDIT_TEST_RULE", "amount > 9999", 20);

        // Deactivate
        authed().patchForObject("/rules/" + rule.getId(),
                new org.springframework.http.HttpEntity<>(
                        java.util.Map.of("active", false),
                        jsonHeaders()),
                Rule.class);

        // Fetch audit history
        ResponseEntity<List<RuleAuditLog>> auditResp = authed().exchange(
                "/rules/" + rule.getId() + "/audit",
                HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {});

        assertThat(auditResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<RuleAuditLog> logs = auditResp.getBody();
        assertThat(logs).hasSizeGreaterThanOrEqualTo(2);
        assertThat(logs.stream().map(RuleAuditLog::getAction))
                .contains("CREATE", "DEACTIVATE");
        assertThat(logs.stream().map(RuleAuditLog::getChangedBy))
                .allMatch(p -> p.equals("analyst"));
    }

    @Test
    void updatedExpression_appliedImmediately() {
        Rule rule = createRule("MUTABLE_RULE", "amount > 99999", 20);

        // Does NOT fire for amount=200
        EvaluationRequest req = baseRequest();
        req.setAmount(new BigDecimal("200.00"));
        assertThat(evaluate(req).getTriggeredRules().stream()
                .noneMatch(r -> r.getName().equals("MUTABLE_RULE"))).isTrue();

        // Update expression to a lower threshold — no restart
        authed().patchForObject("/rules/" + rule.getId(),
                new org.springframework.http.HttpEntity<>(
                        java.util.Map.of("conditionExpression", "amount > 100"),
                        jsonHeaders()),
                Rule.class);

        // Now it must fire
        assertThat(evaluate(req).getTriggeredRules().stream()
                .anyMatch(r -> r.getName().equals("MUTABLE_RULE"))).isTrue();
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }
}
