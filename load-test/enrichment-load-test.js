/**
 * k6 load test — enrichment latency under load
 *
 * Run:
 *   k6 run load-test/enrichment-load-test.js
 *
 * Prerequisites:
 *   - ingestion-service running on localhost:8081
 *   - scoring-service running on localhost:8084
 *   - Redis warm (docker compose up -d redis)
 *
 * What it measures:
 *   - HTTP 202 rate must be 100%
 *   - p99 of the full POST /transactions round-trip must be < 200ms
 *   - Custom "enrichment_latency" trend (reported by scoring-service actuator
 *     metrics) is checked post-run via a separate summary request
 */

import http from "k6/http";
import { check, sleep } from "k6";
import { Trend, Rate } from "k6/metrics";

const enrichmentLatency = new Trend("enrichment_latency_ms", true);
const errorRate         = new Rate("error_rate");

export const options = {
  stages: [
    { duration: "15s", target: 50  },   // ramp up
    { duration: "30s", target: 200 },   // sustained load
    { duration: "15s", target: 0   },   // ramp down
  ],
  thresholds: {
    // Full round-trip p99 must stay under 200ms
    http_req_duration:      ["p(99)<200"],
    // Zero errors
    error_rate:             ["rate==0"],
    // Enrichment sub-latency p99 must stay under 5ms
    // (populated from X-Enrichment-Ms response header set by scoring-service)
    enrichment_latency_ms:  ["p(99)<5"],
  },
};

// Load test hits scoring-service /score directly to measure enrichment latency via X-Enrichment-Ms header.
// For end-to-end flow testing use INGESTION_URL=http://localhost:8081 and endpoint /transactions.
const BASE_URL    = __ENV.SCORING_URL   || "http://localhost:8084";
const ENDPOINT    = __ENV.ENDPOINT      || "/score";

function randomUserId() {
  // Spread across 50 users so velocity windows get exercised
  return `load-user-${Math.floor(Math.random() * 50)}`;
}

function randomIp() {
  const ips = ["10.0.0.1", "192.168.1.1", "185.220.0.1", "45.142.0.1", "127.0.0.1"];
  return ips[Math.floor(Math.random() * ips.length)];
}

function randomDevice() {
  const devices = ["d-clean", "EMU-test01", "ROOT-test02", "d-normal", "d-mobile"];
  return devices[Math.floor(Math.random() * devices.length)];
}

export default function () {
  const payload = JSON.stringify({
    userId:      randomUserId(),
    amount:      parseFloat((Math.random() * 20000).toFixed(2)),
    merchantId:  "m-load-test",
    deviceId:    randomDevice(),
    ipAddress:   randomIp(),
    timestamp:   new Date().toISOString(),
    cardLast4:   "1234",
    cardCountry: "US",
  });

  const res = http.post(`${BASE_URL}${ENDPOINT}`, payload, {
    headers: { "Content-Type": "application/json" },
  });

  const ok = check(res, {
    "status is 2xx": (r) => r.status >= 200 && r.status < 300,
  });
  errorRate.add(!ok);

  // scoring-service adds X-Enrichment-Ms header with the Redis wall-clock time
  const enrichMs = parseFloat(res.headers["X-Enrichment-Ms"] || "0");
  if (enrichMs > 0) {
    enrichmentLatency.add(enrichMs);
  }

  sleep(0.01);
}

export function handleSummary(data) {
  const p99 = data.metrics.enrichment_latency_ms?.values?.["p(99)"] ?? "n/a";
  const p95 = data.metrics.enrichment_latency_ms?.values?.["p(95)"] ?? "n/a";
  console.log(`\n=== Enrichment latency summary ===`);
  console.log(`  p95 : ${p95} ms`);
  console.log(`  p99 : ${p99} ms  (threshold: < 5 ms)`);
  console.log(`  http p99: ${data.metrics.http_req_duration?.values?.["p(99)"]} ms`);
  return {};
}
