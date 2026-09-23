package com.fraudplatform.enrichment.model;

import java.util.Set;

/**
 * Immutable snapshot of enriched signals for a single transaction.
 * All fields have safe neutral defaults so a partial Redis failure
 * never produces a null-pointer in the rules engine.
 */
public record EnrichedFeatures(
        /** Sliding-window transaction count for this userId in the last 5 min. -1 = unavailable. */
        long velocityCount,
        /** Risk flags attached to the deviceId (e.g. "EMULATOR", "ROOTED"). Empty = clean/unknown. */
        Set<String> deviceRiskFlags,
        /** Risk flags attached to the ipAddress (e.g. "TOR_EXIT", "DATACENTER"). Empty = clean/unknown. */
        Set<String> ipRiskFlags
) {
    /** Neutral value returned on any Redis failure — never blocks the transaction. */
    public static EnrichedFeatures neutral() {
        return new EnrichedFeatures(-1, Set.of(), Set.of());
    }
}
