package com.fraudplatform.rules.engine;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

/**
 * Flat context object exposed to SpEL rule expressions.
 *
 * Every field here is a valid SpEL property name. Expressions can reference:
 *   amount, userId, merchantId, deviceId, ipAddress, cardCountry,
 *   velocityCount, deviceRiskFlags, ipRiskFlags, features (raw map)
 *
 * Example expressions:
 *   amount > 5000
 *   velocityCount >= 3 && amount > 1000
 *   deviceRiskFlags.contains('EMULATOR')
 *   ipRiskFlags.contains('TOR_EXIT')
 *   features['customKey'] != null
 */
@Data
@Builder
public class RuleEvaluationContext {

    // ── from TransactionEvent ─────────────────────────────────────────────
    private String     userId;
    private BigDecimal amount;
    private String     merchantId;
    private String     deviceId;
    private String     ipAddress;
    private String     cardCountry;

    // ── from EnrichedFeatures / FeatureSet ────────────────────────────────
    private long        velocityCount;
    private Set<String> deviceRiskFlags;
    private Set<String> ipRiskFlags;

    /** Raw feature map for arbitrary analyst-defined keys. */
    private Map<String, Object> features;
}
