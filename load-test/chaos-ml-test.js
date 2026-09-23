/**
 * k6 chaos test — ML inference failure mid-load
 *
 * What it does:
 *   1. Ramp to 100 VUs hitting POST /score on scoring-service.
 *   2. At t=30s the ML inference service is killed externally (see instructions below).
 *   3. Assert that decisions keep coming (2xx rate stays 100%) — degraded but not down.
 *   4. Assert scoring p99 stays under 3 s even while the CB is tripping.
 *   5. Assert consumer lag metric doesn't grow (checked via Kafka AdminClient in a
 *      separate script — see chaos-lag-check.sh).
 *
 * Prerequisites:
 *   - scoring-service running on localhost:8084
 *   - ml-inference-service running on localhost:8088
 *   - Redis + Postgres + Kafka running (docker compose up -d)
 *
 * Run:
 *   # Terminal 1 — run the load test
 *   k6 run load-test/chaos-ml-test.js
 *
 *   # Terminal 2 — kill ML inference at t≈30s to trigger chaos
 *   sleep 30 && docker stop ml-inference
 *
 *   # Terminal 3 — watch consumer lag
 *   watch -n2 "docker exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
 *     --bootstrap-server localhost:9092 --describe --group scoring-group \
 *     | grep payment-events"
 */

import http from "k6/http";
import { check, sleep } from "k6";
import { Rate, Trend, Counter } from "k6/metrics";

const errorRate          = new Rate("error_rate");
const scoringLatency     = new Trend("scoring_latency_ms", true);
const mlDegradedDecisions = new Counter("ml_degraded_decisions");

export const options = {
  stages: [
    { duration: "15s", target: 50  },   // ramp up
    { duration: "60s", target: 100 },   // sustained — ML killed at ~t=30s externally
    { duration: "15s", target: 0   },   // ramp down
  ],
  thresholds: {
    // Pipeline must keep producing decisions even with ML down
    "error_rate":             ["rate==0"],
    // p99 must stay under 3 s — CB timeout is 2 s, so worst case is one timeout + overhead
    "http_req_duration":      ["p(99)<3000"],
    // Scoring latency (from X-Scoring-Ms header) p99 under 3 s
    "scoring_latency_ms":     ["p(99)<3000"],
  },
};

const BASE_URL = __ENV.SCORING_URL || "http://localhost:8084";

function randomUserId() {
  return `chaos-user-${Math.floor(Math.random() * 100)}`;
}

function randomIp() {
  const ips = ["10.0.0.1", "192.168.1.1", "185.220.0.1", "45.142.0.1", "127.0.0.1"];
  return ips[Math.floor(Math.random() * ips.length)];
}

function randomDevice() {
  const devices = ["d-clean", "EMU-test01", "ROOT-test02", "d-normal"];
  return devices[Math.floor(Math.random() * devices.length)];
}

export default function () {
  const payload = JSON.stringify({
    userId:      randomUserId(),
    amount:      parseFloat((Math.random() * 20000).toFixed(2)),
    merchantId:  "m-chaos-test",
    deviceId:    randomDevice(),
    ipAddress:   randomIp(),
    timestamp:   new Date().toISOString(),
    cardLast4:   "9999",
    cardCountry: "US",
  });

  const res = http.post(`${BASE_URL}/score`, payload, {
    headers: { "Content-Type": "application/json" },
    timeout: "5s",
  });

  const ok = check(res, {
    "status is 2xx":          (r) => r.status >= 200 && r.status < 300,
    "decision field present":  (r) => {
      try { return JSON.parse(r.body).decision !== undefined; } catch { return false; }
    },
  });
  errorRate.add(!ok);

  // Track scoring latency from response header
  const scoringMs = parseFloat(res.headers["X-Scoring-Ms"] || "0");
  if (scoringMs > 0) scoringLatency.add(scoringMs);

  // Count decisions made while ML score is -1 (degraded / rules-only)
  try {
    const body = JSON.parse(res.body);
    if (body.mlScore === -1) mlDegradedDecisions.add(1);
  } catch (_) {}

  sleep(0.05);
}

export function handleSummary(data) {
  const p99Http    = data.metrics.http_req_duration?.values?.["p(99)"]    ?? "n/a";
  const p99Scoring = data.metrics.scoring_latency_ms?.values?.["p(99)"]   ?? "n/a";
  const errRate    = data.metrics.error_rate?.values?.rate                 ?? "n/a";
  const degraded   = data.metrics.ml_degraded_decisions?.values?.count     ?? 0;
  const total      = data.metrics.http_reqs?.values?.count                 ?? 0;

  console.log("\n=== Chaos test summary ===");
  console.log(`  Total requests      : ${total}`);
  console.log(`  Error rate          : ${errRate}`);
  console.log(`  http p99            : ${p99Http} ms`);
  console.log(`  scoring p99         : ${p99Scoring} ms`);
  console.log(`  ML-degraded decisions: ${degraded} / ${total} (rules-only fallback)`);
  console.log(`\n  PASS criteria:`);
  console.log(`    error_rate == 0          : ${errRate == 0 ? "✓" : "✗"}`);
  console.log(`    http p99 < 3000 ms       : ${p99Http < 3000 ? "✓" : "✗"}`);
  console.log(`    degraded decisions > 0   : ${degraded > 0 ? "✓ (CB fired as expected)" : "✗ (ML never failed?)"}`);
  return {};
}
