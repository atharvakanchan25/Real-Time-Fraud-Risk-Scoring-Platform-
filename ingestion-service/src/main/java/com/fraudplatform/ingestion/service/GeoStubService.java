package com.fraudplatform.ingestion.service;

import org.springframework.stereotype.Service;

/**
 * Stub geolocation service. In production this would call a real GeoIP provider.
 * IPs starting with "10." or "192.168." are treated as domestic (US).
 * A small set of known prefixes map to specific countries; everything else → "XX" (unknown).
 */
@Service
public class GeoStubService {

    public String countryForIp(String ip) {
        if (ip == null) return "XX";
        if (ip.startsWith("10.") || ip.startsWith("192.168.") || ip.startsWith("127.")) return "US";
        if (ip.startsWith("185.")) return "RU";
        if (ip.startsWith("103.")) return "CN";
        if (ip.startsWith("5."))   return "DE";
        return "XX";
    }
}
