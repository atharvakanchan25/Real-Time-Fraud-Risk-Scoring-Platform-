package com.fraudplatform.scoring.controller;

import com.fraudplatform.common.dto.PaymentEvent;
import com.fraudplatform.enrichment.facade.EnrichmentFacade;
import com.fraudplatform.enrichment.model.EnrichedFeatures;
import com.fraudplatform.scoring.service.FraudRulesEngine;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Thin HTTP endpoint used by the k6 load test to measure enrichment latency
 * directly. Sets X-Enrichment-Ms response header with the wall-clock time
 * spent in Redis enrichment calls.
 *
 * Not part of the production Kafka flow — scoring-service's primary input
 * is the Kafka consumer.
 */
@RestController
@RequiredArgsConstructor
public class ScoringController {

    private final EnrichmentFacade enrichmentFacade;
    private final FraudRulesEngine rulesEngine;

    @PostMapping("/score")
    public ResponseEntity<Map<String, Object>> score(@Valid @RequestBody PaymentEvent event) {
        long start = System.nanoTime();
        EnrichedFeatures features = enrichmentFacade.enrich(
                event.getUserId(), event.getDeviceId(), event.getIpAddress());
        long enrichmentMs = (System.nanoTime() - start) / 1_000_000;

        FraudRulesEngine.RuleResult result = rulesEngine.evaluate(event);

        return ResponseEntity.accepted()
                .header("X-Enrichment-Ms", String.valueOf(enrichmentMs))
                .body(Map.of(
                        "decision",       result.decision(),
                        "riskScore",      result.riskScore(),
                        "rulesTriggered", result.triggeredRules()
                ));
    }
}
