#!/usr/bin/env bash
# capture-artifacts.sh
#
# Runs the production-scale k6 load test, then captures:
#   results/p99-histogram.png   — latency histogram (gnuplot)
#   results/consumer-lag.png    — Kafka consumer lag over time (Prometheus + gnuplot)
#   results/summary.json        — machine-readable pass/fail (written by k6)
#   results/raw.json            — full k6 JSON output
#
# Prerequisites:
#   - k6        : https://k6.io/docs/getting-started/installation/
#   - gnuplot   : brew install gnuplot  /  apt install gnuplot
#   - curl, jq  : standard
#   - Prometheus scraping scoring-service (for lag graph)
#
# Usage:
#   # Local (docker compose)
#   bash load-test/capture-artifacts.sh
#
#   # Against k8s LoadBalancer
#   INGESTION_URL=http://<LB-IP> PROMETHEUS_URL=http://<prom-IP>:9090 \
#     bash load-test/capture-artifacts.sh

set -euo pipefail

INGESTION_URL="${INGESTION_URL:-http://localhost:8081}"
PROMETHEUS_URL="${PROMETHEUS_URL:-http://localhost:9090}"
RESULTS_DIR="results"
RAW_JSON="$RESULTS_DIR/raw.json"
HIST_DATA="$RESULTS_DIR/latency-histogram.dat"
LAG_DATA="$RESULTS_DIR/consumer-lag.dat"

mkdir -p "$RESULTS_DIR"

echo "▶  Starting production-scale load test..."
echo "   Ingestion URL : $INGESTION_URL"
echo "   Results dir   : $RESULTS_DIR"
echo ""

# ── 1. Run k6 ─────────────────────────────────────────────────────────────────
k6 run \
  --out "json=$RAW_JSON" \
  --env "INGESTION_URL=$INGESTION_URL" \
  load-test/production-scale-test.js

echo ""
echo "▶  k6 finished. Processing artifacts..."

# ── 2. Extract latency histogram data from raw.json ───────────────────────────
# k6 JSON output has one data point per request with metric name + value.
# We bucket into 10 ms bins up to 500 ms.
python3 - "$RAW_JSON" "$HIST_DATA" <<'PYEOF'
import json, sys, collections

raw_path, out_path = sys.argv[1], sys.argv[2]
buckets = collections.Counter()
bucket_size = 10   # ms

with open(raw_path) as f:
    for line in f:
        try:
            obj = json.loads(line)
        except json.JSONDecodeError:
            continue
        if obj.get("type") == "Point" and obj.get("metric") == "ingestion_latency_ms":
            val = obj["data"]["value"]
            b = int(val // bucket_size) * bucket_size
            b = min(b, 500)   # cap at 500 ms
            buckets[b] += 1

with open(out_path, "w") as f:
    f.write("# bucket_ms  count\n")
    for b in sorted(buckets):
        f.write(f"{b}  {buckets[b]}\n")

print(f"  Histogram data written: {out_path} ({sum(buckets.values())} data points)")
PYEOF

# ── 3. Plot latency histogram ─────────────────────────────────────────────────
P99=$(jq -r '.latency.p99 // "n/a"' "$RESULTS_DIR/summary.json" 2>/dev/null || echo "n/a")
RPS=$(jq -r '.actualRps   // "n/a"' "$RESULTS_DIR/summary.json" 2>/dev/null || echo "n/a")

gnuplot <<GNUPLOT
set terminal pngcairo size 900,500 enhanced font "Helvetica,12"
set output "$RESULTS_DIR/p99-histogram.png"
set title "Ingestion-service Latency Histogram\n(~${RPS} RPS sustained, p99 = ${P99} ms)" font "Helvetica,14"
set xlabel "Latency bucket (ms)"
set ylabel "Request count"
set style fill solid 0.7 border -1
set boxwidth 8
set grid ytics
set key off
set xrange [-5:510]
plot "$HIST_DATA" using 1:2 with boxes lc rgb "#4C72B0" title "requests"
GNUPLOT
echo "  ✓ Latency histogram → $RESULTS_DIR/p99-histogram.png"

# ── 4. Fetch Kafka consumer lag from Prometheus ───────────────────────────────
# Query: sum(kafka_consumergroup_lag{consumergroup="scoring-group"}) over last 5 min
# Adjust the range to match your test duration.
echo ""
echo "▶  Fetching consumer lag from Prometheus ($PROMETHEUS_URL)..."

STEP=15   # seconds between data points
END=$(date +%s)
START=$(( END - 300 ))   # last 5 minutes

PROM_QUERY='sum(kafka_consumergroup_lag{consumergroup="scoring-group"})'
ENCODED_QUERY=$(python3 -c "import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1]))" "$PROM_QUERY")

HTTP_STATUS=$(curl -s -o "$RESULTS_DIR/lag-raw.json" -w "%{http_code}" \
  "${PROMETHEUS_URL}/api/v1/query_range?query=${ENCODED_QUERY}&start=${START}&end=${END}&step=${STEP}")

if [[ "$HTTP_STATUS" != "200" ]]; then
  echo "  ⚠  Prometheus returned HTTP $HTTP_STATUS — skipping lag graph."
  echo "     (Is Prometheus running and scraping kafka-lag-exporter / kminion?)"
else
  # Convert Prometheus range result to gnuplot data
  python3 - "$RESULTS_DIR/lag-raw.json" "$LAG_DATA" <<'PYEOF'
import json, sys

raw_path, out_path = sys.argv[1], sys.argv[2]
with open(raw_path) as f:
    data = json.load(f)

results = data.get("data", {}).get("result", [])
if not results:
    print("  No lag data returned from Prometheus.")
    sys.exit(0)

values = results[0].get("values", [])
with open(out_path, "w") as f:
    f.write("# epoch_s  lag\n")
    for ts, val in values:
        f.write(f"{ts}  {val}\n")

print(f"  Lag data written: {out_path} ({len(values)} points)")
PYEOF

  if [[ -f "$LAG_DATA" ]]; then
    gnuplot <<GNUPLOT
set terminal pngcairo size 900,400 enhanced font "Helvetica,12"
set output "$RESULTS_DIR/consumer-lag.png"
set title "Kafka Consumer Lag — scoring-group / payment-events\n(during production-scale test)" font "Helvetica,14"
set xlabel "Time (epoch s)"
set ylabel "Consumer lag (messages)"
set grid
set key off
set style line 1 lc rgb "#E05C5C" lw 2
plot "$LAG_DATA" using 1:2 with lines ls 1 title "lag"
GNUPLOT
    echo "  ✓ Consumer lag graph → $RESULTS_DIR/consumer-lag.png"
  fi
fi

# ── 5. Print final summary ────────────────────────────────────────────────────
echo ""
echo "══════════════════════════════════════════════════"
echo "  ARTIFACTS"
echo "══════════════════════════════════════════════════"
for f in "$RESULTS_DIR/summary.json" \
          "$RESULTS_DIR/p99-histogram.png" \
          "$RESULTS_DIR/consumer-lag.png" \
          "$RESULTS_DIR/raw.json"; do
  if [[ -f "$f" ]]; then
    SIZE=$(du -sh "$f" | cut -f1)
    echo "  ✓  $f  ($SIZE)"
  else
    echo "  ✗  $f  (not generated)"
  fi
done
echo ""

PASSED=$(jq -r '.thresholdsPassed' "$RESULTS_DIR/summary.json" 2>/dev/null || echo "unknown")
if [[ "$PASSED" == "true" ]]; then
  echo "  ✅  All thresholds PASSED — ready for portfolio."
else
  echo "  ❌  One or more thresholds FAILED — see results/summary.json."
  exit 1
fi
