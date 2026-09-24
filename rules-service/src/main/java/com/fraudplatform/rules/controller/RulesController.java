package com.fraudplatform.rules.controller;

import com.fraudplatform.rules.dto.*;
import com.fraudplatform.rules.entity.Rule;
import com.fraudplatform.rules.entity.RuleAuditLog;
import com.fraudplatform.rules.service.EvaluationService;
import com.fraudplatform.rules.service.RuleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/rules")
@RequiredArgsConstructor
public class RulesController {

    private final RuleService ruleService;
    private final EvaluationService evaluationService;

    @GetMapping
    public List<Rule> list() {
        return ruleService.listAll();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Rule create(@Valid @RequestBody CreateRuleRequest req, Authentication auth) {
        return ruleService.create(req, auth.getName());
    }

    @PatchMapping("/{id}")
    public Rule update(@PathVariable Long id,
                       @RequestBody UpdateRuleRequest req,
                       Authentication auth) {
        return ruleService.update(id, req, auth.getName());
    }

    @GetMapping("/{id}/audit")
    public List<RuleAuditLog> audit(@PathVariable Long id) {
        return ruleService.auditHistory(id);
    }

    @PostMapping("/evaluate")
    public EvaluationResponse evaluate(@Valid @RequestBody EvaluationRequest req) {
        return evaluationService.evaluate(req);
    }
}
