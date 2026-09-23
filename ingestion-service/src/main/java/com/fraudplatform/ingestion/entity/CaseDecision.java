package com.fraudplatform.ingestion.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

@Data
@Entity
@Table(name = "case_decisions", indexes = {
        @Index(name = "idx_cd_user_id", columnList = "user_id"),
        @Index(name = "idx_cd_created_at", columnList = "created_at")
})
public class CaseDecision {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "transaction_id", nullable = false, unique = true)
    private String transactionId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(nullable = false)
    private String status;   // ALLOW | REVIEW | BLOCK

    @Column(name = "risk_score", nullable = false)
    private int riskScore;

    @Column(name = "rules_triggered")
    private String rulesTriggered;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
