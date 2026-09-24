package com.fraudplatform.enrichment.reputation;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * Stub reputation lookup. In production this would call an internal risk-data API.
 * Returns a set of string risk flags for a given identifier.
 */
@Component
public class ReputationStub {

    // Prefix → flag mappings shared by both device and IP lookups
    private static final Map<String, Set<String>> DEVICE_PREFIX_FLAGS = Map.of(
        "EMU-",  Set.of("EMULATOR"),
        "ROOT-", Set.of("ROOTED"),
        "BAD-",  Set.of("EMULATOR", "ROOTED")
    );

    private static final Map<String, Set<String>> IP_PREFIX_FLAGS = Map.of(
        "185.220.", Set.of("TOR_EXIT"),
        "45.142.",  Set.of("DATACENTER"),
        "198.51.",  Set.of("TOR_EXIT", "DATACENTER")
    );

    public Set<String> deviceFlags(String deviceId) {
        return flagsForPrefix(deviceId, DEVICE_PREFIX_FLAGS);
    }

    public Set<String> ipFlags(String ipAddress) {
        return flagsForPrefix(ipAddress, IP_PREFIX_FLAGS);
    }

    private Set<String> flagsForPrefix(String value, Map<String, Set<String>> prefixMap) {
        if (value == null) return Set.of();
        return prefixMap.entrySet().stream()
                .filter(e -> value.startsWith(e.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(Set.of());
    }
}
