package com.fraudplatform.rules.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

@Data
@Entity
@Table(name = "rules", indexes = {
        @Index(name = "idx_rules_active", columnList = "active"),
        @Index(name = "idx_rules_name_version", columnList = "name, version")
})
public class Rule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    /**
     * SpEL expression evaluated against a {@code RuleEvaluationContext}.
     * Example: {@code amount > 5000 && velocityCount >= 3}
     */
    @Column(name = "condition_expression", nullable = false, length = 1024)
    private String conditionExpression;

    /**
     * Contribution to the aggregate risk score (0–100).
     */
    @Column(nullable = false)
    private int weight;

    @Column(nullable = false)
    private boolean active = true;

    /**
     * Monotonically increasing per rule name. Allows history without deleting rows.
     */
    @Column(nullable = false)
    private int version = 1;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
