package com.fraudplatform.scoring.controller;

import com.fraudplatform.common.dto.PaymentEvent;
import com.fraudplatform.enrichment.facade.EnrichmentFacade;
import com.fraudplatform.enrichment.model.EnrichedFeatures;
import com.fraudplatform.scoring.client.MlInferenceClient;
import com.fraudplatform.scoring.service.FraudRulesEngine;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

@RestController
@RequiredArgsConstructor
public class ScoringController {

    private final EnrichmentFacade enrichmentFacade;
    private final FraudRulesEngine rulesEngine;
    private final MlInferenceClient mlInferenceClient;

    @PostMapping("/score")
    public ResponseEntity<Map<String, Object>> score(@Valid @RequestBody PaymentEvent event) {
        long start = System.nanoTime();
        EnrichedFeatures features = enrichmentFacade.enrich(
                event.getUserId(), event.getDeviceId(), event.getIpAddress());
        long enrichmentMs = (System.nanoTime() - start) / 1_000_000;

        Optional<Double> mlScore = mlInferenceClient.infer(
                event.getUserId(), event.getAmount(), event.getDeviceId(), event.getIpAddress());

        FraudRulesEngine.RuleResult result = rulesEngine.evaluate(event, mlScore);

        return ResponseEntity.accepted()
                .header("X-Enrichment-Ms", String.valueOf(enrichmentMs))
                .header("X-Scoring-Ms",    String.valueOf((System.nanoTime() - start) / 1_000_000))
                .body(Map.of(
                        "decision",       result.decision(),
                        "riskScore",      result.riskScore(),
                        "rulesTriggered", result.triggeredRules(),
                        "mlScore",        mlScore.orElse(-1.0)
                ));
    }
}
