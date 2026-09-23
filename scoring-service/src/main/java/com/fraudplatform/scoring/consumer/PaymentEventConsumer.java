package com.fraudplatform.scoring.consumer;

import com.fraudplatform.common.dto.PaymentEvent;
import com.fraudplatform.scoring.service.ScoringService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventConsumer {

    private final ScoringService scoringService;

    @KafkaListener(
            topics = "${kafka.topics.payment-events}",
            groupId = "${spring.kafka.consumer.group-id}",
            concurrency = "6"   // one thread per partition; scale instances to add more
    )
    public void consume(
            PaymentEvent event,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset) {

        log.info("Received event key={} partition={} offset={}", event.getIdempotencyKey(), partition, offset);
        scoringService.score(event);
    }
}
