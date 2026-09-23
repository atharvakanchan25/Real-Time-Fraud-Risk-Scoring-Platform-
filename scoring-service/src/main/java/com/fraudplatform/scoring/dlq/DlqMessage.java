package com.fraudplatform.scoring.dlq;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

@Data
@Entity
@Table(name = "dlq_messages", indexes = {
        @Index(name = "idx_dlq_created_at", columnList = "created_at"),
        @Index(name = "idx_dlq_replayed",   columnList = "replayed")
})
public class DlqMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "user_id")
    private String userId;

    /** Full JSON payload of the original PaymentEvent. */
    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    /** Last exception message that caused the DLQ routing. */
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /** Set to true once the message has been successfully replayed. */
    @Column(nullable = false)
    private boolean replayed = false;
}
