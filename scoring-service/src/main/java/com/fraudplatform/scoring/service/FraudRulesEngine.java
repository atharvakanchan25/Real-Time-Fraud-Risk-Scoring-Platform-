package com.fraudplatform.scoring.service;

import com.fraudplatform.common.dto.PaymentEvent;
import com.fraudplatform.scoring.repository.TransactionRepository;
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
            if (riskScore < 30)  return "ALLOW";
            if (riskScore <= 70) return "REVIEW";
            return "BLOCK";
        }
    }

    public RuleResult evaluate(PaymentEvent event) {
        List<String> triggered = new ArrayList<>();

        if (event.getAmount().compareTo(amountThreshold) > 0) {
            triggered.add("HIGH_AMOUNT");
        }

        Instant since = Instant.now().minus(velocityWindowMinutes, ChronoUnit.MINUTES);
        if (transactionRepository.countByUserIdSince(event.getUserId(), since) >= velocityMaxCount) {
            triggered.add("HIGH_VELOCITY");
        }

        String ipCountry = geoStubService.countryForIp(event.getIpAddress());
        if (!"XX".equals(ipCountry) && !ipCountry.equalsIgnoreCase(event.getCardCountry())) {
            triggered.add("GEO_MISMATCH");
        }

        return new RuleResult(triggered, Math.min(100, triggered.size() * 35));
    }
}
