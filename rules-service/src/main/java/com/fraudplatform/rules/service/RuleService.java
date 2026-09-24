package com.fraudplatform.rules.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraudplatform.rules.dto.CreateRuleRequest;
import com.fraudplatform.rules.dto.UpdateRuleRequest;
import com.fraudplatform.rules.engine.SpelRulesEngine;
import com.fraudplatform.rules.entity.Rule;
import com.fraudplatform.rules.entity.RuleAuditLog;
import com.fraudplatform.rules.repository.RuleAuditLogRepository;
import com.fraudplatform.rules.repository.RuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RuleService {

    private final RuleRepository ruleRepository;
    private final RuleAuditLogRepository auditLogRepository;
    private final SpelRulesEngine spelRulesEngine;
    private final ObjectMapper objectMapper;

    public List<Rule> listAll() {
        return ruleRepository.findAll();
    }

    @Transactional
    public Rule create(CreateRuleRequest req, String principal) {
        Rule rule = new Rule();
        rule.setName(req.getName());
        rule.setConditionExpression(req.getConditionExpression());
        rule.setWeight(req.getWeight());
        rule.setActive(true);
        rule.setVersion(1);
        rule = ruleRepository.save(rule);

        audit(rule, "CREATE", principal, null, rule);
        spelRulesEngine.invalidateCache();
        return rule;
    }

    @Transactional
    public Rule update(Long id, UpdateRuleRequest req, String principal) {
        Rule rule = findOrThrow(id);
        String before = snapshot(rule);

        if (req.getConditionExpression() != null) {
            rule.setConditionExpression(req.getConditionExpression());
            rule.setVersion(rule.getVersion() + 1);
        }
        if (req.getWeight() != null)  rule.setWeight(req.getWeight());
        if (req.getActive() != null)  rule.setActive(req.getActive());
        rule.setUpdatedAt(Instant.now());

        rule = ruleRepository.save(rule);
        String action = req.getActive() != null
                ? (req.getActive() ? "ACTIVATE" : "DEACTIVATE")
                : "UPDATE";
        audit(rule, action, principal, before, rule);
        spelRulesEngine.invalidateCache();
        return rule;
    }

    public List<RuleAuditLog> auditHistory(Long ruleId) {
        return auditLogRepository.findByRuleIdOrderByChangedAtDesc(ruleId);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Rule findOrThrow(Long id) {
        return ruleRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Rule not found: " + id));
    }

    private void audit(Rule rule, String action, String principal, String before, Rule after) {
        auditLogRepository.save(RuleAuditLog.builder()
                .ruleId(rule.getId())
                .ruleName(rule.getName())
                .action(action)
                .changedBy(principal)
                .changedAt(Instant.now())
                .beforeState(before)
                .afterState(snapshot(after))
                .build());
    }

    private String snapshot(Rule rule) {
        try {
            return objectMapper.writeValueAsString(rule);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
