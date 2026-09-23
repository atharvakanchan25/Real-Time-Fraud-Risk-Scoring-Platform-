package com.fraudplatform.casemanagement.consumer;

import com.fraudplatform.casemanagement.entity.FraudCase;
import com.fraudplatform.casemanagement.repository.FraudCaseRepository;
import com.fraudplatform.common.dto.FinalDecisionEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class FinalDecisionConsumer {

    private final FraudCaseRepository caseRepository;

    @KafkaListener(
            topics = "${kafka.topics.final-decisions}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(FinalDecisionEvent event) {
        if (!event.getOutcome().equals("REVIEW") && !event.getOutcome().equals("BLOCK")) {
            return; // APPROVE decisions don't need a case
        }
        if (caseRepository.existsByTransactionId(event.getTransactionId())) {
            log.warn("Duplicate final-decision skipped: {}", event.getTransactionId());
            return;
        }

        FraudCase fraudCase = new FraudCase();
        fraudCase.setTransactionId(event.getTransactionId());
        fraudCase.setUserId(event.getUserId());
        fraudCase.setDeviceId(event.getDeviceId());
        fraudCase.setInitialOutcome(event.getOutcome());
        fraudCase.setFraudScore(event.getFraudScore());
        fraudCase.setRuleTriggered(event.getRuleTriggered());

        caseRepository.save(fraudCase);
        log.info("Case created: txId={} outcome={}", event.getTransactionId(), event.getOutcome());
    }
}
