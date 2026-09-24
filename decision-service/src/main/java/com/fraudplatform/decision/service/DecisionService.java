package com.fraudplatform.decision.service;

import com.fraudplatform.common.dto.DecisionEvent;
import com.fraudplatform.common.dto.FinalDecisionEvent;
import com.fraudplatform.decision.config.DecisionThresholds;
import com.fraudplatform.decision.entity.FinalDecision;
import com.fraudplatform.decision.repository.FinalDecisionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DecisionService {

    private final FinalDecisionRepository repository;
    private final DecisionThresholds thresholds;
    private final KafkaTemplate<String, FinalDecisionEvent> kafkaTemplate;

    @Value("${kafka.topics.final-decisions}")
    private String finalDecisionsTopic;

    @Transactional
    public void process(DecisionEvent event) {
        if (repository.existsById(event.getTransactionId())) {
            log.warn("Duplicate decision-event skipped: {}", event.getTransactionId());
            return;
        }

        String outcome = applyThresholds(event.getFraudScore());

        FinalDecision fd = new FinalDecision();
        fd.setTransactionId(event.getTransactionId());
        fd.setUserId(event.getUserId() != null ? event.getUserId() : event.getTransactionId());
        fd.setDeviceId(event.getDeviceId());
        fd.setOutcome(outcome);
        fd.setFraudScore(event.getFraudScore());
        fd.setRuleTriggered(event.getRuleTriggered());
        fd.setDecidedAt(event.getDecidedAt());
        repository.save(fd);

        FinalDecisionEvent out = FinalDecisionEvent.builder()
                .transactionId(event.getTransactionId())
                .userId(event.getUserId() != null ? event.getUserId() : event.getTransactionId())
                .deviceId(event.getDeviceId())
                .outcome(outcome)
                .fraudScore(event.getFraudScore())
                .ruleTriggered(event.getRuleTriggered())
                .decidedAt(event.getDecidedAt())
                .build();

        kafkaTemplate.send(finalDecisionsTopic, event.getTransactionId(), out);
        log.info("Final decision: txId={} score={} outcome={}", event.getTransactionId(), event.getFraudScore(), outcome);
    }

    private String applyThresholds(double score) {
        if (score < thresholds.approveBelow()) return "APPROVE";
        if (score > thresholds.blockAbove())   return "BLOCK";
        return "REVIEW";
    }
}
