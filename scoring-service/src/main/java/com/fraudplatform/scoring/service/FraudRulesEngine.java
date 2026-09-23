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
import java.util.Optional;

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

    /**
     * Evaluates rules and blends with an optional ML score.
     *
     * Blending strategy (when ML score is present):
     *   finalScore = 0.6 * rulesScore + 0.4 * (mlProbability * 100)
     * When ML is unavailable (circuit open / timeout), falls back to rules-only:
     *   finalScore = rulesScore
     */
    public RuleResult evaluate(PaymentEvent event, Optional<Double> mlProbability) {
        EnrichedFeatures features = enrichmentFacade.enrich(
                event.getUserId(), event.getDeviceId(), event.getIpAddress());

        List<String> triggered = new ArrayList<>();

        if (event.getAmount().compareTo(amountThreshold) > 0)
            triggered.add("HIGH_AMOUNT");

        if (features.velocityCount() >= velocityMaxCount)
            triggered.add("HIGH_VELOCITY");

        String ipCountry = geoStubService.countryForIp(event.getIpAddress());
        if (!"XX".equals(ipCountry) && !ipCountry.equalsIgnoreCase(event.getCardCountry()))
            triggered.add("GEO_MISMATCH");

        if (!features.deviceRiskFlags().isEmpty())
            triggered.add("RISKY_DEVICE");

        if (!features.ipRiskFlags().isEmpty())
            triggered.add("RISKY_IP");

        int rulesScore = Math.min(100, triggered.size() * 20);

        int finalScore = mlProbability
                .map(p -> (int) Math.round(0.6 * rulesScore + 0.4 * (p * 100)))
                .orElse(rulesScore);

        return new RuleResult(triggered, finalScore, features);
    }

    /** Convenience overload — rules-only, no ML score. */
    public RuleResult evaluate(PaymentEvent event) {
        return evaluate(event, Optional.empty());
    }
}
