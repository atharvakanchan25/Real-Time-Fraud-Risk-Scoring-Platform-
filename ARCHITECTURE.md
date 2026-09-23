# Architecture Notes — Phase 2

## Service split

```
POST /transactions
      │
      ▼
ingestion-service (port 8081)
  • validates & normalises payload
  • assigns UUID idempotency key
  • publishes PaymentEvent to payment-events (key = userId)
  • returns 202 ACCEPTED immediately — no business logic
      │
      │  topic: payment-events (6 partitions, keyed by userId)
      ▼
scoring-service (port 8084)
  • consumes payment-events (group: scoring-group, concurrency = 6)
  • runs fraud rules in-process
  • persists Transaction + CaseDecision to Postgres
  • publishes DecisionEvent to decision-events (key = userId)
```

## Delivery semantics: idempotent producer, not Kafka transactions

**Choice: idempotent producers (`enable.idempotence=true`) on both services.**

### Why not Kafka transactions (exactly-once)?

Kafka transactions give exactly-once semantics across a consume→produce loop by
atomically committing the consumer offset and the produced message in a single
transaction. That is the right tool when the *only* side effect is another Kafka
write (e.g. a pure stream-processing topology).

scoring-service also writes to **Postgres** inside the same logical unit of work.
A Kafka transaction cannot span two different transactional resources (Kafka +
Postgres) without a distributed transaction coordinator (XA/2PC), which adds
significant operational complexity and latency.

### What we do instead

1. **Idempotent producer** (`enable.idempotence=true`, `acks=all`,
   `max.in.flight.requests.per.connection=5`) — the broker deduplicates retried
   produce requests using a producer-epoch + sequence number, so a network blip
   never causes a duplicate message on the topic. This is always-on and has no
   throughput cost.

2. **At-least-once consumer** (`enable.auto.commit=false` via Spring's default
   manual-ack mode with `@KafkaListener`) — the offset is committed only after
   `ScoringService.score()` returns successfully. If the service crashes mid-way,
   the message is redelivered.

3. **Application-level idempotency guard** in `ScoringService` — the
   `idempotencyKey` (UUID assigned by ingestion-service) is the primary key of
   the `transactions` table. A duplicate delivery simply hits the
   `existsById` check and is skipped without touching Postgres, making the
   consumer effectively idempotent.

The combination of (1) + (2) + (3) gives **effectively-once** processing with
no distributed transaction overhead.

## Partition-by-userId ordering guarantee

All messages for a given `userId` are routed to the same partition because the
Kafka key is set to `userId` in both ingestion-service and scoring-service.
Kafka guarantees ordering within a partition. The `concurrency = 6` listener
setting assigns one thread per partition, so no two threads ever race on the
same user's events.

## Horizontal scaling

To scale scoring-service, deploy additional instances with the same
`group-id = scoring-group`. Kafka will rebalance partitions across instances
automatically. With 6 partitions the maximum useful parallelism is 6 instances.
Increase partition count before deploying more than 6 instances (partition count
can only be increased, never decreased).

---

## Phase 3a — Feature enrichment via Redis

### Synchronous library vs async service

enrichment-service is packaged as a **plain library jar** consumed directly by
scoring-service. The enrichment calls happen synchronously on the Kafka listener
thread before the rules engine runs.

**Why synchronous for now:**
- Eliminates a network hop and a second Kafka round-trip.
- Keeps the latency budget simple: one service, one Redis call, one decision.
- At current scale (single scoring-service instance, ~200 RPS) the Redis RTT
  is well under 1 ms on a local network, so there is no throughput penalty.

**When to split into its own async service:**
- When enrichment sources multiply (ML feature store, third-party APIs, graph DB)
  and their latencies become heterogeneous — async fan-out with a join becomes
  cheaper than sequential blocking calls.
- When enrichment needs independent scaling (e.g. reputation lookups spike during
  a fraud wave while scoring throughput stays flat).
- When you want to cache enriched feature sets across multiple downstream consumers
  (scoring-service, rules-service, decision-service) — a shared enriched-events
  topic avoids each service doing its own Redis calls.
- Rule of thumb: split when the enrichment p99 latency exceeds ~10 ms or when
  more than two services need the same enriched data.

### Redis data structures

| Purpose | Key pattern | Structure | TTL |
|---|---|---|---|
| Sliding-window velocity | `velocity:{userId}` | ZSET (score = epoch-ms) | window + 10 s |
| Device reputation cache | `rep:device:{deviceId}` | Hash `flags` field | 1 h |
| IP reputation cache | `rep:ip:{ipAddress}` | Hash `flags` field | 1 h |

### Resilience4j guard (5 ms hard timeout)

Every Redis call goes through `RedisGuard`, which wraps the call in:

1. `TimeLimiter` (5 ms) — submits the call to a virtual-thread executor and
   cancels it if it hasn't returned within the budget.
2. `CircuitBreaker` — opens after 50% failure rate over a 20-call window,
   stays open for 10 s. While open, calls short-circuit immediately to the
   fallback without touching Redis.

Fallback values:
- `velocityCount = -1` → velocity rule is skipped (safe, not blocking)
- `deviceRiskFlags = {}` → device treated as clean
- `ipRiskFlags = {}`    → IP treated as clean

This means a Redis outage degrades enrichment quality but **never stalls or
errors a transaction**.

### Load test

`load-test/enrichment-load-test.js` (k6):
- Ramps to 200 VUs over 60 s.
- Hits `POST /score` on scoring-service directly.
- scoring-service sets `X-Enrichment-Ms` header with the wall-clock Redis time.
- k6 threshold: `enrichment_latency_ms p(99) < 5 ms` with Redis warm.

Run:
```bash
# Start infrastructure
docker compose up -d redis postgres kafka

# Start scoring-service
cd scoring-service && mvn spring-boot:run &

# Run load test
k6 run load-test/enrichment-load-test.js
```
