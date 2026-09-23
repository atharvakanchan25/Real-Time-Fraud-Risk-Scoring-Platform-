package com.fraudplatform.scoring.service;

import org.springframework.stereotype.Service;

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
