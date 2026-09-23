package com.fraudplatform.feedback.consumer;

import com.fraudplatform.common.dto.FeedbackEvent;
import com.fraudplatform.feedback.service.ReputationUpdateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class FeedbackConsumer {

    private final ReputationUpdateService reputationUpdateService;

    @KafkaListener(
            topics = "${spring.kafka.consumer.feedback-topic:feedback-events}",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void consume(FeedbackEvent event) {
        log.debug("FeedbackEvent received: txId={} verdict={}", event.getTransactionId(), event.getVerdict());
        if ("CONFIRMED_FRAUD".equals(event.getVerdict())) {
            reputationUpdateService.markHighRisk(event.getUserId(), event.getDeviceId());
        }
    }
}
