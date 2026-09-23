package com.fraudplatform.scoring.service;

import com.fraudplatform.common.dto.DecisionEvent;
import com.fraudplatform.common.dto.PaymentEvent;
import com.fraudplatform.scoring.client.MlInferenceClient;
import com.fraudplatform.scoring.entity.CaseDecision;
import com.fraudplatform.scoring.entity.Transaction;
import com.fraudplatform.scoring.metrics.ScoringMetrics;
import com.fraudplatform.scoring.repository.CaseDecisionRepository;
import com.fraudplatform.scoring.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScoringService {

    private final TransactionRepository transactionRepository;
    private final CaseDecisionRepository caseDecisionRepository;
    private final FraudRulesEngine rulesEngine;
    private final GeoStubService geoStubService;
    private final MlInferenceClient mlInferenceClient;
    private final ScoringMetrics scoringMetrics;
    private final KafkaTemplate<String, DecisionEvent> kafkaTemplate;

    @Value("${kafka.topics.decision-events}")
    private String decisionEventsTopic;

    @Transactional
    public void score(PaymentEvent event) {
        long start = System.nanoTime();
        try {
            if (transactionRepository.existsById(event.getIdempotencyKey())) {
                log.warn("Duplicate event skipped: {}", event.getIdempotencyKey());
                return;
            }

            Transaction tx = new Transaction();
            tx.setIdempotencyKey(event.getIdempotencyKey());
            tx.setUserId(event.getUserId());
            tx.setAmount(event.getAmount());
            tx.setMerchantId(event.getMerchantId());
            tx.setDeviceId(event.getDeviceId());
            tx.setIpAddress(event.getIpAddress());
            tx.setCardLast4(event.getCardLast4());
            tx.setCardCountry(event.getCardCountry());
            tx.setIpCountry(geoStubService.countryForIp(event.getIpAddress()));
            transactionRepository.save(tx);

            // ML inference — circuit breaker falls back to Optional.empty() on failure
            Optional<Double> mlScore = mlInferenceClient.infer(
                    event.getUserId(), event.getAmount(), event.getDeviceId(), event.getIpAddress());

            FraudRulesEngine.RuleResult result = rulesEngine.evaluate(event, mlScore);

            CaseDecision decision = new CaseDecision();
            decision.setTransactionId(event.getIdempotencyKey());
            decision.setUserId(event.getUserId());
            decision.setStatus(result.decision());
            decision.setRiskScore(result.riskScore());
            decision.setRulesTriggered(String.join(",", result.triggeredRules()));
            caseDecisionRepository.save(decision);

            DecisionEvent decisionEvent = DecisionEvent.builder()
                    .transactionId(event.getIdempotencyKey())
                    .decision(result.decision())
                    .fraudScore(result.riskScore())
                    .ruleTriggered(String.join(",", result.triggeredRules()))
                    .decidedAt(Instant.now())
                    .build();

            kafkaTemplate.send(decisionEventsTopic, event.getUserId(), decisionEvent);
        } finally {
            scoringMetrics.recordScoringLatency(System.nanoTime() - start);
        }
    }
}
