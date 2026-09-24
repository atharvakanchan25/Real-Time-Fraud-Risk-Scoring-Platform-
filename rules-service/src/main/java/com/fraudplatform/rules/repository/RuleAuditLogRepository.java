package com.fraudplatform.rules.repository;

import com.fraudplatform.rules.entity.RuleAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RuleAuditLogRepository extends JpaRepository<RuleAuditLog, Long> {
    List<RuleAuditLog> findByRuleIdOrderByChangedAtDesc(Long ruleId);
}
