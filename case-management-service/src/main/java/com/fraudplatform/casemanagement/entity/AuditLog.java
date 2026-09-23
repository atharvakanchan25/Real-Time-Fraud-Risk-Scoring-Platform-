package com.fraudplatform.casemanagement.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

@Data
@Entity
@Table(name = "audit_logs", indexes = {
        @Index(name = "idx_al_case_id",    columnList = "case_id"),
        @Index(name = "idx_al_analyst",    columnList = "analyst"),
        @Index(name = "idx_al_created_at", columnList = "created_at")
})
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "case_id", nullable = false)
    private Long caseId;

    @Column(name = "transaction_id", nullable = false)
    private String transactionId;

    @Column(nullable = false)
    private String analyst;

    @Column(nullable = false)
    private String verdict;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
