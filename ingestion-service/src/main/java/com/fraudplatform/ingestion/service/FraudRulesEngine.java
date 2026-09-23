package com.fraudplatform.ingestion.service;

import com.fraudplatform.ingestion.dto.FraudRequest;
import com.fraudplatform.ingestion.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class FraudRulesEngine {

    private final TransactionRepository transactionRepository;
    private final GeoStubService geoStubService;

    @Value("${fraud.rules.amount-threshold:10000}")
    private BigDecimal amountThreshold;

    @Value("${fraud.rules.velocity-max-count:5}")
    private int velocityMaxCount;

    @Value("${fraud.rules.velocity-window-minutes:5}")
    private int velocityWindowMinutes;

    public record RuleResult(List<String> triggeredRules, int riskScore) {
        public String decision() {
            if (riskScore < 30) return "ALLOW";
            if (riskScore <= 70) return "REVIEW";
            return "BLOCK";
        }
    }

    public RuleResult evaluate(FraudRequest req) {
        List<String> triggered = new ArrayList<>();

        // Rule 1 — high amount
        if (req.getAmount().compareTo(amountThreshold) > 0) {
            triggered.add("HIGH_AMOUNT");
        }

        // Rule 2 — velocity: too many transactions from same user in last N minutes
        Instant since = Instant.now().minus(velocityWindowMinutes, ChronoUnit.MINUTES);
        long recentCount = transactionRepository.countByUserIdSince(req.getUserId(), since);
        if (recentCount >= velocityMaxCount) {
            triggered.add("HIGH_VELOCITY");
        }

        // Rule 3 — IP country mismatch with card issuing country
        String ipCountry = geoStubService.countryForIp(req.getIpAddress());
        if (!"XX".equals(ipCountry) && !ipCountry.equalsIgnoreCase(req.getCardCountry())) {
            triggered.add("GEO_MISMATCH");
        }

        int score = Math.min(100, triggered.size() * 35);
        return new RuleResult(triggered, score);
    }
}
