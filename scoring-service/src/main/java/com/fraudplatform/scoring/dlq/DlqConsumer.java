package com.fraudplatform.scoring.dlq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraudplatform.common.dto.PaymentEvent;
import com.fraudplatform.scoring.metrics.ScoringMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DlqConsumer {

    private final DlqMessageRepository dlqMessageRepository;
    private final ScoringMetrics scoringMetrics;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics  = "${kafka.topics.payment-events-dlq}",
            groupId = "${spring.kafka.consumer.group-id}-dlq"
    )
    public void consume(
            PaymentEvent event,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            @Header(value = "kafka_dlt-exception-message", required = false) String errorMessage) {

        log.error("DLQ message received: key={} partition={} offset={} error={}",
                event.getIdempotencyKey(), partition, offset, errorMessage);

        DlqMessage msg = new DlqMessage();
        msg.setIdempotencyKey(event.getIdempotencyKey());
        msg.setUserId(event.getUserId());
        msg.setErrorMessage(errorMessage);
        try {
            msg.setPayload(objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            msg.setPayload("{\"error\":\"serialization failed\"}");
        }
        dlqMessageRepository.save(msg);
        scoringMetrics.incrementDlqCount();
    }
}
