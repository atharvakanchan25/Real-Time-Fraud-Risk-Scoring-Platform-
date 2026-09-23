package com.fraudplatform.decision.consumer;

import com.fraudplatform.common.dto.DecisionEvent;
import com.fraudplatform.decision.service.DecisionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DecisionEventConsumer {

    private final DecisionService decisionService;

    @KafkaListener(
            topics = "${kafka.topics.decision-events}",
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(DecisionEvent event) {
        log.debug("Received decision-event: txId={} score={}", event.getTransactionId(), event.getFraudScore());
        decisionService.process(event);
    }
}
