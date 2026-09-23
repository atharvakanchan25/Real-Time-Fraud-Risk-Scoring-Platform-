package com.fraudplatform.ingestion.service;

import com.fraudplatform.common.dto.PaymentEvent;
import com.fraudplatform.ingestion.dto.TransactionRequest;
import com.fraudplatform.ingestion.dto.TransactionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class IngestionService {

    private final KafkaTemplate<String, PaymentEvent> kafkaTemplate;

    @Value("${kafka.topics.payment-events}")
    private String paymentEventsTopic;

    public TransactionResponse ingest(TransactionRequest req) {
        String idempotencyKey = UUID.randomUUID().toString();

        PaymentEvent event = PaymentEvent.builder()
                .idempotencyKey(idempotencyKey)
                .userId(req.getUserId())
                .amount(req.getAmount())
                .merchantId(req.getMerchantId())
                .deviceId(req.getDeviceId())
                .ipAddress(req.getIpAddress())
                .timestamp(req.getTimestamp())
                .cardLast4(req.getCardLast4())
                .cardCountry(req.getCardCountry())
                .build();

        // userId as key → same user always lands on the same partition → ordering guarantee
        kafkaTemplate.send(paymentEventsTopic, req.getUserId(), event);

        return TransactionResponse.builder()
                .idempotencyKey(idempotencyKey)
                .status("ACCEPTED")
                .build();
    }
}
