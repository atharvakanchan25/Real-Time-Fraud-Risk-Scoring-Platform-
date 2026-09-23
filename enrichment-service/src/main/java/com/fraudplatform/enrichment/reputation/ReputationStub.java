package com.fraudplatform.enrichment.reputation;

import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Stub reputation lookup. In production this would call an internal risk-data API.
 * Returns a set of string risk flags for a given identifier.
 */
@Component
public class ReputationStub {

    public Set<String> deviceFlags(String deviceId) {
        if (deviceId == null) return Set.of();
        if (deviceId.startsWith("EMU-"))  return Set.of("EMULATOR");
        if (deviceId.startsWith("ROOT-")) return Set.of("ROOTED");
        if (deviceId.startsWith("BAD-"))  return Set.of("EMULATOR", "ROOTED");
        return Set.of();
    }

    public Set<String> ipFlags(String ipAddress) {
        if (ipAddress == null) return Set.of();
        if (ipAddress.startsWith("185.220.")) return Set.of("TOR_EXIT");
        if (ipAddress.startsWith("45.142."))  return Set.of("DATACENTER");
        if (ipAddress.startsWith("198.51."))  return Set.of("TOR_EXIT", "DATACENTER");
        return Set.of();
    }
}
