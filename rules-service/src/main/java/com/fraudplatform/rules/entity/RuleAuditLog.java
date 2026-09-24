package com.fraudplatform.rules.entity;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "rule_audit_log", indexes = {
        @Index(name = "idx_audit_rule_id",   columnList = "rule_id"),
        @Index(name = "idx_audit_changed_at", columnList = "changed_at")
})
public class RuleAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rule_id", nullable = false)
    private Long ruleId;

    @Column(name = "rule_name", nullable = false)
    private String ruleName;

    /** CREATE | UPDATE | ACTIVATE | DEACTIVATE */
    @Column(nullable = false, length = 20)
    private String action;

    @Column(name = "changed_by", nullable = false)
    private String changedBy;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt = Instant.now();

    /** JSON snapshot of the rule before the change (null for CREATE). */
    @Column(name = "before_state", columnDefinition = "TEXT")
    private String beforeState;

    /** JSON snapshot of the rule after the change. */
    @Column(name = "after_state", columnDefinition = "TEXT")
    private String afterState;
}
