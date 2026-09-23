package com.fraudplatform.scoring.service;

import com.fraudplatform.common.dto.PaymentEvent;
import com.fraudplatform.enrichment.facade.EnrichmentFacade;
import com.fraudplatform.enrichment.model.EnrichedFeatures;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class FraudRulesEngine {

    private final EnrichmentFacade enrichmentFacade;
    private final GeoStubService geoStubService;

    @Value("${fraud.rules.amount-threshold:10000}")
    private BigDecimal amountThreshold;

    @Value("${fraud.rules.velocity-max-count:5}")
    private int velocityMaxCount;

    public record RuleResult(List<String> triggeredRules, int riskScore, EnrichedFeatures features) {
        public String decision() {
            if (riskScore < 30)  return "ALLOW";
            if (riskScore <= 70) return "REVIEW";
            return "BLOCK";
        }
    }

    public RuleResult evaluate(PaymentEvent event) {
        // Enrich first — records velocity in Redis, fetches reputation
        EnrichedFeatures features = enrichmentFacade.enrich(
                event.getUserId(), event.getDeviceId(), event.getIpAddress());

        List<String> triggered = new ArrayList<>();

        // Rule 1 — high amount
        if (event.getAmount().compareTo(amountThreshold) > 0) {
            triggered.add("HIGH_AMOUNT");
        }

        // Rule 2 — Redis sliding-window velocity (falls back gracefully when velocityCount == -1)
        if (features.velocityCount() >= velocityMaxCount) {
            triggered.add("HIGH_VELOCITY");
        }

        // Rule 3 — geo mismatch
        String ipCountry = geoStubService.countryForIp(event.getIpAddress());
        if (!"XX".equals(ipCountry) && !ipCountry.equalsIgnoreCase(event.getCardCountry())) {
            triggered.add("GEO_MISMATCH");
        }

        // Rule 4 — risky device
        if (!features.deviceRiskFlags().isEmpty()) {
            triggered.add("RISKY_DEVICE");
        }

        // Rule 5 — risky IP (TOR exit / datacenter)
        if (!features.ipRiskFlags().isEmpty()) {
            triggered.add("RISKY_IP");
        }

        // Score: each rule contributes 20 points, capped at 100
        int score = Math.min(100, triggered.size() * 20);
        return new RuleResult(triggered, score, features);
    }
}
