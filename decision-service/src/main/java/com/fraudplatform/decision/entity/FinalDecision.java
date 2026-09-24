package com.fraudplatform.decision.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

@Data
@Entity
@Table(name = "final_decisions", indexes = {
        @Index(name = "idx_fd_user_id",    columnList = "user_id"),
        @Index(name = "idx_fd_outcome",    columnList = "outcome"),
        @Index(name = "idx_fd_decided_at", columnList = "decided_at")
})
public class FinalDecision {

    @Id
    @Column(name = "transaction_id")
    private String transactionId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "device_id")
    private String deviceId;

    /** APPROVE | REVIEW | BLOCK */
    @Column(nullable = false)
    private String outcome;

    @Column(name = "fraud_score", nullable = false)
    private double fraudScore;

    @Column(name = "rule_triggered")
    private String ruleTriggered;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
