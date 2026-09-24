package com.fraudplatform.rules.engine;

import com.fraudplatform.rules.entity.Rule;
import com.fraudplatform.rules.repository.RuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class SpelRulesEngine {

    private final RuleRepository ruleRepository;
    private final ExpressionParser parser = new SpelExpressionParser();

    /**
     * Compiled expression cache: ruleId → compiled Expression.
     * Invalidated by {@link #invalidateCache()} on any rule mutation.
     */
    private final Map<Long, Expression> expressionCache = new ConcurrentHashMap<>();

    public record TriggeredRule(Long ruleId, String name, int weight, String expression) {}

    /**
     * Evaluates all active rules against {@code ctx}.
     * Always reads the live active-rule list from the DB so a newly added/activated
     * rule is picked up on the very next call — no restart required.
     */
    public List<TriggeredRule> evaluate(RuleEvaluationContext ctx) {
        List<Rule> activeRules = ruleRepository.findByActiveTrue();
        StandardEvaluationContext spelCtx = new StandardEvaluationContext(ctx);

        return activeRules.stream()
                .filter(rule -> safeEvaluate(rule, spelCtx))
                .map(rule -> new TriggeredRule(rule.getId(), rule.getName(),
                        rule.getWeight(), rule.getConditionExpression()))
                .toList();
    }

    /** Called by RuleService after any create/update/activate/deactivate. */
    public void invalidateCache() {
        expressionCache.clear();
    }

    private boolean safeEvaluate(Rule rule, StandardEvaluationContext spelCtx) {
        try {
            Expression expr = expressionCache.computeIfAbsent(
                    rule.getId(), id -> parser.parseExpression(rule.getConditionExpression()));
            Boolean result = expr.getValue(spelCtx, Boolean.class);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            log.warn("Rule '{}' (id={}) expression error: {}", rule.getName(), rule.getId(), e.getMessage());
            return false;
        }
    }
}
