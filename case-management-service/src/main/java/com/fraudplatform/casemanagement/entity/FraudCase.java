package com.fraudplatform.casemanagement.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

@Data
@Entity
@Table(name = "fraud_cases", indexes = {
        @Index(name = "idx_fc_status",     columnList = "status"),
        @Index(name = "idx_fc_created_at", columnList = "created_at")
})
public class FraudCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_id", nullable = false, unique = true)
    private String transactionId;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "device_id")
    private String deviceId;

    /** REVIEW | BLOCK */
    @Column(name = "initial_outcome", nullable = false)
    private String initialOutcome;

    @Column(name = "fraud_score", nullable = false)
    private double fraudScore;

    @Column(name = "rule_triggered")
    private String ruleTriggered;

    /** OPEN | CLOSED */
    @Column(nullable = false)
    private String status = "OPEN";

    /** CONFIRMED_FRAUD | FALSE_POSITIVE | null while open */
    @Column
    private String verdict;

    @Column(name = "reviewed_by")
    private String reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
