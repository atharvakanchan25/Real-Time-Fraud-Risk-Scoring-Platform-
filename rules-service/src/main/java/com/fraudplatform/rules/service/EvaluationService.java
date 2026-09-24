package com.fraudplatform.rules.service;

import com.fraudplatform.rules.dto.EvaluationRequest;
import com.fraudplatform.rules.dto.EvaluationResponse;
import com.fraudplatform.rules.engine.RuleEvaluationContext;
import com.fraudplatform.rules.engine.SpelRulesEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class EvaluationService {

    private final SpelRulesEngine spelRulesEngine;

    public EvaluationResponse evaluate(EvaluationRequest req) {
        RuleEvaluationContext ctx = RuleEvaluationContext.builder()
                .userId(req.getUserId())
                .amount(req.getAmount())
                .merchantId(req.getMerchantId())
                .deviceId(req.getDeviceId())
                .ipAddress(req.getIpAddress())
                .cardCountry(req.getCardCountry())
                .velocityCount(req.getVelocityCount())
                .deviceRiskFlags(req.getDeviceRiskFlags() != null ? req.getDeviceRiskFlags() : java.util.Set.of())
                .ipRiskFlags(req.getIpRiskFlags() != null ? req.getIpRiskFlags() : java.util.Set.of())
                .features(req.getFeatures() != null ? req.getFeatures() : java.util.Map.of())
                .build();

        List<SpelRulesEngine.TriggeredRule> triggered = spelRulesEngine.evaluate(ctx);

        int totalWeight = triggered.stream().mapToInt(SpelRulesEngine.TriggeredRule::weight).sum();
        String decision = totalWeight == 0 ? "ALLOW" : totalWeight <= 40 ? "REVIEW" : "BLOCK";

        List<EvaluationResponse.TriggeredRule> dtos = triggered.stream()
                .map(t -> EvaluationResponse.TriggeredRule.builder()
                        .ruleId(t.ruleId())
                        .name(t.name())
                        .weight(t.weight())
                        .expression(t.expression())
                        .build())
                .toList();

        return EvaluationResponse.builder()
                .triggeredRules(dtos)
                .totalWeight(totalWeight)
                .decision(decision)
                .build();
    }
}
