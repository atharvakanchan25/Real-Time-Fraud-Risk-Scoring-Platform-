package com.fraudplatform.enrichment.facade;

import com.fraudplatform.enrichment.model.EnrichedFeatures;
import com.fraudplatform.enrichment.reputation.ReputationEnrichment;
import com.fraudplatform.enrichment.velocity.VelocityEnrichment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class EnrichmentFacade {

    private final VelocityEnrichment velocityEnrichment;
    private final ReputationEnrichment reputationEnrichment;

    /**
     * Enriches a single transaction. Each sub-call has its own 5 ms Redis guard,
     * so the worst-case total is ~10 ms (two independent timeouts) — still well
     * within a typical 50 ms scoring budget.
     */
    public EnrichedFeatures enrich(String userId, String deviceId, String ipAddress) {
        long velocity     = velocityEnrichment.recordAndCount(userId);
        var  deviceFlags  = reputationEnrichment.deviceFlags(deviceId);
        var  ipFlags      = reputationEnrichment.ipFlags(ipAddress);
        return new EnrichedFeatures(velocity, deviceFlags, ipFlags);
    }
}
