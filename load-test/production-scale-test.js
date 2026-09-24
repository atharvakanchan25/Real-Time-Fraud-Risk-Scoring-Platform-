/**
 * k6 production-scale load test
 * Target: 8,000 – 10,000 events/sec against ingestion-service
 *
 * Run (local):
 *   k6 run --out json=results/raw.json load-test/production-scale-test.js
 *
 * Run (against k8s LoadBalancer):
 *   INGESTION_URL=http://<LB-IP> k6 run \
 *     --out json=results/raw.json \
 *     load-test/production-scale-test.js
 *
 * Artifacts produced (by capture-artifacts.sh):
 *   results/p99-histogram.png   — latency histogram image via gnuplot
 *   results/consumer-lag.png    — Kafka lag graph via Prometheus API + gnuplot
 *   results/summary.json        — machine-readable thresholds pass/fail
 *
 * Throughput math:
 *   Each VU fires one request then sleeps 1 ms (effectively ~1000 req/VU/s).
 *   500 VUs × ~18 req/s (accounting for ~55 ms p99 RTT) ≈ 9,000 req/s.
 *   Adjust TARGET_VUS and SLEEP_MS via env vars if your cluster is faster/slower.
 */

import http    from "k6/http";
import { check, sleep } from "k6";
import { Trend, Rate, Counter } from "k6/metrics";
import { textSummary } from "https://jslib.k6.io/k6-summary/0.0.2/index.js";

// ── Custom metrics ────────────────────────────────────────────────────────────
const ingestionLatency = new Trend("ingestion_latency_ms", true);
const errorRate        = new Rate("error_rate");
const acceptedCount    = new Counter("accepted_events");
const rejectedCount    = new Counter("rejected_events");

// ── Config ────────────────────────────────────────────────────────────────────
const BASE_URL  = __ENV.INGESTION_URL || "http://localhost:8081";
const SLEEP_MS  = parseFloat(__ENV.SLEEP_MS  || "0");   // 0 = fire as fast as possible
const TARGET_VUS = parseInt(__ENV.TARGET_VUS || "500");

export const options = {
  scenarios: {
    ramp_to_peak: {
      executor: "ramping-vus",
      startVUs: 0,
      stages: [
        { duration: "30s",  target: 100         },  // warm-up
        { duration: "60s",  target: TARGET_VUS  },  // ramp to peak
        { duration: "120s", target: TARGET_VUS  },  // sustain at peak (capture evidence here)
        { duration: "30s",  target: 0           },  // ramp down
      ],
      gracefulRampDown: "10s",
    },
  },

  thresholds: {
    // ── Correctness ──────────────────────────────────────────────────────────
    // All requests must return 202 ACCEPTED
    error_rate:             ["rate<0.001"],          // < 0.1% errors

    // ── Latency (ingestion-service is async — just validates + publishes) ────
    // p99 < 150 ms at 9k RPS is the portfolio target
    http_req_duration:      ["p(99)<150", "p(95)<80"],
    ingestion_latency_ms:   ["p(99)<150"],

    // ── Throughput ───────────────────────────────────────────────────────────
    // At least 8,000 requests completed per second during the sustained phase.
    // k6 doesn't have a native RPS threshold; we assert via accepted_events count:
    // 120 s sustained × 8000 RPS = 960,000 minimum accepted events.
    accepted_events:        ["count>960000"],
  },
};

// ── Helpers ───────────────────────────────────────────────────────────────────
const USERS     = Array.from({ length: 200 }, (_, i) => `prod-user-${i}`);
const MERCHANTS = ["m-amazon", "m-stripe", "m-shopify", "m-paypal", "m-square"];
const DEVICES   = ["d-ios-clean", "d-android-clean", "d-web-clean",
                   "EMU-test01", "ROOT-test02", "d-vpn-01"];
const IPS       = ["10.0.0.1", "10.0.0.2", "185.220.0.1", "45.142.0.1",
                   "192.168.1.1", "203.0.113.5", "198.51.100.7"];
const COUNTRIES = ["US", "GB", "DE", "FR", "CN", "RU", "BR"];

function pick(arr) { return arr[Math.floor(Math.random() * arr.length)]; }

function buildPayload() {
  const cardCountry = pick(COUNTRIES);
  return JSON.stringify({
    userId:      pick(USERS),
    amount:      parseFloat((Math.random() * 25000).toFixed(2)),
    merchantId:  pick(MERCHANTS),
    deviceId:    pick(DEVICES),
    ipAddress:   pick(IPS),
    timestamp:   new Date().toISOString(),
    cardLast4:   String(Math.floor(1000 + Math.random() * 9000)),
    cardCountry,
  });
}

// ── Main VU loop ──────────────────────────────────────────────────────────────
export default function () {
  const start = Date.now();

  const res = http.post(`${BASE_URL}/transactions`, buildPayload(), {
    headers: { "Content-Type": "application/json" },
    timeout: "5s",
  });

  const latencyMs = Date.now() - start;
  ingestionLatency.add(latencyMs);

  const ok = check(res, {
    "202 ACCEPTED": (r) => r.status === 202,
    "has idempotencyKey": (r) => {
      try { return !!JSON.parse(r.body).idempotencyKey; } catch { return false; }
    },
  });

  if (ok) {
    acceptedCount.add(1);
  } else {
    rejectedCount.add(1);
    errorRate.add(1);
  }

  if (SLEEP_MS > 0) sleep(SLEEP_MS / 1000);
}

// ── Summary artifact ──────────────────────────────────────────────────────────
export function handleSummary(data) {
  const m   = data.metrics;
  const p50 = m.ingestion_latency_ms?.values?.["p(50)"]  ?? "n/a";
  const p95 = m.ingestion_latency_ms?.values?.["p(95)"]  ?? "n/a";
  const p99 = m.ingestion_latency_ms?.values?.["p(99)"]  ?? "n/a";
  const max = m.ingestion_latency_ms?.values?.max         ?? "n/a";
  const rps = m.http_reqs?.values?.rate                   ?? "n/a";
  const err = m.error_rate?.values?.rate                  ?? "n/a";
  const acc = m.accepted_events?.values?.count            ?? 0;

  const passed = (p99 !== "n/a" && p99 < 150) && (err < 0.001) && (acc > 960000);

  const summary = {
    timestamp:   new Date().toISOString(),
    targetRps:   "8000-10000",
    actualRps:   rps,
    latency: { p50, p95, p99, max },
    errorRate:   err,
    acceptedEvents: acc,
    thresholdsPassed: passed,
  };

  console.log("\n╔══════════════════════════════════════════════════╗");
  console.log("║       PRODUCTION SCALE TEST — SUMMARY            ║");
  console.log("╠══════════════════════════════════════════════════╣");
  console.log(`║  Actual RPS        : ${String(rps).padEnd(26)}║`);
  console.log(`║  Latency p50       : ${String(p50 + " ms").padEnd(26)}║`);
  console.log(`║  Latency p95       : ${String(p95 + " ms").padEnd(26)}║`);
  console.log(`║  Latency p99       : ${String(p99 + " ms").padEnd(26)}║`);
  console.log(`║  Latency max       : ${String(max + " ms").padEnd(26)}║`);
  console.log(`║  Error rate        : ${String(err).padEnd(26)}║`);
  console.log(`║  Accepted events   : ${String(acc).padEnd(26)}║`);
  console.log(`║  All thresholds    : ${passed ? "✓ PASS" : "✗ FAIL"}${" ".repeat(20)}║`);
  console.log("╚══════════════════════════════════════════════════╝\n");

  return {
    "results/summary.json": JSON.stringify(summary, null, 2),
    stdout: textSummary(data, { indent: " ", enableColors: true }),
  };
}
