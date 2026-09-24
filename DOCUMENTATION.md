# Fraud Detection Platform — Full Technical Documentation

---

## Table of Contents

1. [Overview](#1-overview)
2. [System Architecture](#2-system-architecture)
3. [How a Transaction Flows End to End](#3-how-a-transaction-flows-end-to-end)
4. [Shared Data Model (common module)](#4-shared-data-model-common-module)
5. [Service Deep Dives](#5-service-deep-dives)
   - [ingestion-service](#51-ingestion-service)
   - [enrichment-service](#52-enrichment-service)
   - [rules-service](#53-rules-service)
   - [scoring-service](#54-scoring-service)
   - [decision-service](#55-decision-service)
   - [case-management-service](#56-case-management-service)
   - [feedback-service](#57-feedback-service)
   - [ml-inference-service](#58-ml-inference-service)
6. [Kafka Topics](#6-kafka-topics)
7. [Database Schema](#7-database-schema)
8. [Redis Data Structures](#8-redis-data-structures)
9. [Fraud Scoring Logic](#9-fraud-scoring-logic)
10. [Resilience and Fault Tolerance](#10-resilience-and-fault-tolerance)
11. [Security](#11-security)
12. [Observability](#12-observability)
13. [API Reference](#13-api-reference)
14. [Configuration Reference](#14-configuration-reference)
15. [Running Locally](#15-running-locally)
16. [Kubernetes and Production](#16-kubernetes-and-production)

---

## 1. Overview

This platform checks every payment transaction for fraud in real time and returns one of three decisions:

- **APPROVE** — transaction looks clean, let it through
- **REVIEW** — suspicious, send to a human analyst
- **BLOCK** — high confidence fraud, reject immediately

The system is built as 8 independent microservices that talk to each other through Apache Kafka. Each service does exactly one job. When a transaction comes in, it travels through the pipeline asynchronously — the caller gets a `202 Accepted` response immediately and the decision is made in the background within milliseconds.

### Technology Stack

| Layer | Technology |
|---|---|
| Language | Java 17 |
| Framework | Spring Boot 3 |
| Messaging | Apache Kafka 3.7 |
| Cache / Feature store | Redis 7 |
| Database | PostgreSQL 15 |
| ML model server | Spring Boot stub (replaceable with SageMaker / TorchServe) |
| Tracing | OpenTelemetry + Jaeger |
| Metrics | Micrometer + Prometheus + Grafana |
| Resilience | Resilience4j (circuit breaker, time limiter) |
| Build | Maven 3.9 |
| Container runtime | Docker / Docker Compose |

---

## 2. System Architecture

```
                          ┌─────────────────────────────────────────────────────┐
                          │                  MICROSERVICES                       │
                          │                                                      │
  Client ──POST /tx──▶   │  ingestion-service :8081                             │
                          │       │                                              │
                          │       │ payment-events (Kafka)                       │
                          │       ▼                                              │
                          │  enrichment-service :8082 ◀──▶ Redis                │
                          │       │                                              │
                          │       │ enriched-transactions (Kafka)                │
                          │       ├─────────────────────┐                       │
                          │       ▼                     ▼                       │
                          │  rules-service :8083   scoring-service :8084        │
                          │       │                     │ ◀──▶ ml-inference:8088│
                          │       │ rule-signals        │ fraud-scores          │
                          │       └──────────┬──────────┘                       │
                          │                  ▼                                  │
                          │          decision-service :8085                     │
                          │                  │                                  │
                          │                  │ final-decisions (Kafka)          │
                          │                  ├──────────────────┐               │
                          │                  ▼                  ▼               │
                          │  case-management-service :8086  feedback-service    │
                          │       │ ◀──▶ PostgreSQL            :8087 ◀──▶ Redis │
                          │       │ feedback-events (Kafka)                     │
                          └───────┼─────────────────────────────────────────────┘
                                  │
                          ┌───────▼──────────────────────────────────────────┐
                          │              OBSERVABILITY                        │
                          │  Jaeger :16686   Prometheus :9090   Grafana :3000 │
                          └──────────────────────────────────────────────────┘
```

---

## 3. How a Transaction Flows End to End

Here is the exact sequence of events when a payment is submitted:

**Step 1 — Client submits a transaction**
The client sends a `POST /transactions` request to `ingestion-service` with fields like `userId`, `amount`, `deviceId`, and `ipAddress`.

**Step 2 — ingestion-service validates and publishes**
The service validates the request, assigns a UUID as an idempotency key, builds a `PaymentEvent`, and publishes it to the `payment-events` Kafka topic keyed by `userId`. It immediately returns `202 Accepted` to the caller.

**Step 3 — enrichment-service adds context**
The service consumes the `PaymentEvent` and makes three Redis lookups:
- How many transactions has this user made in the last 5 minutes? (velocity)
- Is this device flagged as risky?
- Is this IP address flagged as risky?

It attaches these signals as `EnrichedFeatures` and the enriched transaction fans out to both `rules-service` and `scoring-service` in parallel.

**Step 4a — rules-service evaluates fraud rules**
Loads all active rules from PostgreSQL and evaluates each one using Spring Expression Language (SpEL). Each matching rule contributes its `weight` to a rule score. Publishes a `rule-signals` event.

**Step 4b — scoring-service runs the ML model**
Calls `ml-inference-service` via HTTP to get a fraud probability (0.0–1.0). Blends the ML score with the rules score using a 60/40 weighting. Publishes a `fraud-scores` event.

**Step 5 — decision-service makes the final call**
Consumes both `rule-signals` and `fraud-scores`, applies the threshold logic, and publishes a `FinalDecisionEvent` to the `final-decisions` topic:
- Score < 30 → APPROVE
- Score 30–70 → REVIEW
- Score > 70 → BLOCK

**Step 6a — case-management-service creates a case**
If the outcome is REVIEW or BLOCK, a `FraudCase` is saved to PostgreSQL for a human analyst to investigate.

**Step 6b — feedback-service updates reputation**
When an analyst marks a case as `CONFIRMED_FRAUD`, the feedback-service writes a `HIGH_RISK` flag into Redis against the user and device. The next transaction from that user or device will be enriched with elevated risk signals.

---

## 4. Shared Data Model (common module)

All services share a `common` Maven module that contains the Kafka message DTOs. No service depends on another service's internal classes — only these shared DTOs.

### PaymentEvent
Published by `ingestion-service` to `payment-events`.

| Field | Type | Description |
|---|---|---|
| `idempotencyKey` | String (UUID) | Unique ID for this transaction |
| `userId` | String | The user making the payment |
| `amount` | BigDecimal | Transaction amount |
| `merchantId` | String | Merchant receiving the payment |
| `deviceId` | String | Device used for the payment |
| `ipAddress` | String | IP address of the request |
| `timestamp` | Instant | When the transaction was initiated |
| `cardLast4` | String | Last 4 digits of the card |
| `cardCountry` | String | ISO-3166 country of the card issuer |

### DecisionEvent
Published by `scoring-service` to `decision-events`.

| Field | Type | Description |
|---|---|---|
| `transactionId` | String | Links back to the PaymentEvent idempotencyKey |
| `decision` | String | APPROVE / REVIEW / BLOCK |
| `fraudScore` | double | Combined fraud score 0–100 |
| `ruleTriggered` | String | Comma-separated list of triggered rule names |
| `userId` | String | User ID |
| `deviceId` | String | Device ID |
| `decidedAt` | Instant | Timestamp of the decision |

### FinalDecisionEvent
Published by `decision-service` to `final-decisions`.

| Field | Type | Description |
|---|---|---|
| `transactionId` | String | Transaction ID |
| `userId` | String | User ID |
| `deviceId` | String | Device ID |
| `outcome` | String | APPROVE / REVIEW / BLOCK |
| `fraudScore` | double | Final fraud score |
| `ruleTriggered` | String | Rules that fired |
| `decidedAt` | Instant | Decision timestamp |

### FeedbackEvent
Published by `case-management-service` to `feedback-events`.

| Field | Type | Description |
|---|---|---|
| `caseId` | String | The fraud case ID |
| `transactionId` | String | Original transaction ID |
| `userId` | String | User ID |
| `deviceId` | String | Device ID |
| `verdict` | String | CONFIRMED_FRAUD or FALSE_POSITIVE |
| `analyst` | String | Username of the analyst |
| `decidedAt` | Instant | When the verdict was submitted |

---

## 5. Service Deep Dives

### 5.1 ingestion-service

**Port:** 8081

The front door of the platform. Its only job is to accept transactions, validate them, and put them on the Kafka queue. It does zero fraud logic.

**What it does:**
1. Receives `POST /transactions` with a JSON body
2. Validates all required fields using Bean Validation (`@NotBlank`, `@NotNull`, `@DecimalMin`)
3. Generates a UUID as the `idempotencyKey` — this ID follows the transaction through every service
4. Builds a `PaymentEvent` and publishes it to the `payment-events` Kafka topic
5. Uses `userId` as the Kafka message key — this guarantees all transactions from the same user land on the same partition, preserving order
6. Returns `202 Accepted` immediately — the caller does not wait for a fraud decision

**Key classes:**
- `TransactionController` — REST endpoint
- `IngestionService` — builds the event and calls KafkaTemplate
- `TransactionRequest` — validated input DTO
- `TransactionResponse` — returns the `idempotencyKey` and status `ACCEPTED`

**Kafka producer config:**
- `acks=all` — waits for all replicas to acknowledge before returning
- `enable.idempotence=true` — broker deduplicates retried produce requests
- `retries=MAX_INT` — retries indefinitely on transient failures

---

### 5.2 enrichment-service

**Port:** 8082

Adds context to a raw transaction before it reaches the fraud engine. It answers three questions using Redis:
1. How many times has this user transacted in the last 5 minutes?
2. Is this device known to be risky?
3. Is this IP address known to be risky?

**Velocity tracking — how it works:**

Uses a Redis Sorted Set (ZSET) with the key `velocity:{userId}`. Each transaction is added as a member with its epoch-millisecond timestamp as the score. On every call:
1. Add the current timestamp to the ZSET
2. Remove all entries older than the 5-minute window
3. Count remaining entries — that is the velocity count
4. Set a TTL slightly longer than the window so the key self-cleans

**Reputation lookup — how it works:**

Uses Redis Hashes with keys `rep:device:{deviceId}` and `rep:ip:{ipAddress}`. The `flags` field stores a comma-separated list of risk flags (e.g. `EMULATOR,ROOTED` or `TOR_EXIT`). On a cache miss, it calls `ReputationStub` (a placeholder for a real reputation API) and writes the result back with a 1-hour TTL.

**Redis resilience — RedisGuard:**

Every Redis call is wrapped in a `RedisGuard` that applies:
- A **5ms hard timeout** via Resilience4j `TimeLimiter` — if Redis doesn't respond in 5ms, the call is cancelled
- A **circuit breaker** — opens after 50% failure rate over 20 calls, stays open for 10 seconds

If Redis is unavailable, the fallback values are:
- `velocityCount = -1` (velocity rule is skipped, not blocked)
- `deviceRiskFlags = {}` (device treated as clean)
- `ipRiskFlags = {}` (IP treated as clean)

A Redis outage degrades enrichment quality but never stops a transaction from being processed.

**Key classes:**
- `EnrichmentFacade` — orchestrates all three enrichment calls
- `VelocityEnrichment` — sliding-window ZSET logic
- `ReputationEnrichment` — hash-based reputation cache
- `RedisGuard` — wraps calls with timeout + circuit breaker
- `EnrichedFeatures` — immutable record returned to the caller

---

### 5.3 rules-service

**Port:** 8083  
**Auth:** HTTP Basic (analyst / analyst)

Stores fraud rules in PostgreSQL and evaluates them using Spring Expression Language (SpEL). Rules can be added, changed, or disabled at any time without restarting the service.

**How live rule updates work:**

Every evaluation call reads the active rule list directly from PostgreSQL — there is no in-memory cache of which rules are active. Compiled `Expression` objects are cached in a `ConcurrentHashMap` keyed by rule ID for performance. When any rule is created, updated, or toggled, `invalidateCache()` clears this map. The next evaluation recompiles from the fresh DB state.

This means:
- A new rule is live on the **very next** evaluation call after `POST /rules`
- A deactivated rule is excluded on the **very next** call after `PATCH /rules/{id}`
- No restart, no redeploy, no cache TTL to wait for

**Rule fields:**

| Field | Type | Description |
|---|---|---|
| `id` | Long | Auto-generated primary key |
| `name` | String | Human-readable rule name |
| `conditionExpression` | String (SpEL) | The rule logic, e.g. `amount > 10000` |
| `weight` | int | Contribution to the fraud score (0–100) |
| `active` | boolean | Whether the rule is currently evaluated |
| `version` | int | Increments on every expression change |
| `createdAt` | Instant | Creation timestamp |
| `updatedAt` | Instant | Last modification timestamp |

**Available fields in rule expressions:**

| Field | Type | Example rule |
|---|---|---|
| `amount` | BigDecimal | `amount > 10000` |
| `velocityCount` | long | `velocityCount >= 5` |
| `deviceRiskFlags` | Set\<String\> | `deviceRiskFlags.contains('EMULATOR')` |
| `ipRiskFlags` | Set\<String\> | `ipRiskFlags.contains('TOR_EXIT')` |

**Audit log:**

Every create, update, activate, and deactivate action is recorded in the `rule_audit_log` table with:
- Who made the change (`changedBy` — the HTTP Basic username)
- When it happened (`changedAt`)
- Full JSON snapshots of the rule before and after the change

**Key classes:**
- `SpelRulesEngine` — evaluates rules, manages expression cache
- `RuleService` — CRUD operations + audit logging
- `EvaluationService` — handles `POST /rules/evaluate` requests
- `RulesController` — REST endpoints
- `Rule` — JPA entity
- `RuleAuditLog` — JPA entity for audit history

---

### 5.4 scoring-service

**Port:** 8084

The brain of the platform. Consumes payment events, runs enrichment, evaluates fraud rules, calls the ML model, blends the scores, and publishes a decision.

**Processing pipeline per transaction:**

1. Check if `idempotencyKey` already exists in the `transactions` table — if yes, skip (duplicate delivery protection)
2. Save the transaction to PostgreSQL
3. Call `EnrichmentFacade` to get velocity count and reputation flags from Redis
4. Call `MlInferenceClient` to get a fraud probability from `ml-inference-service`
5. Run `FraudRulesEngine.evaluate()` to check built-in rules
6. Blend ML score and rules score into a final score
7. Save the `CaseDecision` to PostgreSQL
8. Publish a `DecisionEvent` to the `decision-events` Kafka topic

**Score blending formula:**

When ML is available:
```
finalScore = 0.6 × rulesScore + 0.4 × (mlProbability × 100)
```

When ML is unavailable (circuit breaker open):
```
finalScore = rulesScore
```

**Built-in rules evaluated by FraudRulesEngine:**

| Rule | Trigger condition |
|---|---|
| `HIGH_AMOUNT` | `amount > $10,000` (configurable) |
| `HIGH_VELOCITY` | `velocityCount >= 5` in last 5 minutes (configurable) |
| `GEO_MISMATCH` | IP country does not match card issuing country |
| `RISKY_DEVICE` | `deviceRiskFlags` is not empty |
| `RISKY_IP` | `ipRiskFlags` is not empty |

Each triggered rule adds 20 points to the rules score (capped at 100).

**Kafka consumer config:**
- `concurrency = 6` — one listener thread per partition
- `auto-offset-reset = earliest` — starts from the beginning on first run
- Offset is committed only after `score()` returns successfully (at-least-once delivery)

**Circuit breakers:**
- `ml-inference` — falls back to rules-only scoring if ML service is down
- `rules-service` — falls back to embedded `FraudRulesEngine` if rules-service HTTP call fails

**Dead letter queue:**
Failed messages that cannot be processed after retries are published to `payment-events-dlq` and stored in the `dlq_messages` table for manual inspection.

**Key classes:**
- `PaymentEventConsumer` — Kafka listener
- `ScoringService` — orchestrates the full pipeline
- `FraudRulesEngine` — built-in rule evaluation + score blending
- `MlInferenceClient` — HTTP client for ML model with circuit breaker
- `RulesServiceClient` — HTTP client for rules-service with circuit breaker
- `ScoringMetrics` — records scoring latency as a Micrometer histogram

---

### 5.5 decision-service

**Port:** 8085

Consumes `DecisionEvent` messages from `scoring-service`, applies the score thresholds, persists the final decision, and publishes a `FinalDecisionEvent`.

**Threshold logic:**

| Fraud score | Outcome |
|---|---|
| < 30 | APPROVE |
| 30 – 70 | REVIEW |
| > 70 | BLOCK |

Thresholds are configurable via `decision.thresholds.approve-below` and `decision.thresholds.block-above` in `application.yml`.

**Idempotency:** Checks if `transactionId` already exists in the `final_decisions` table before processing. Duplicate events are silently skipped.

**Key classes:**
- `DecisionEventConsumer` — Kafka listener
- `DecisionService` — applies thresholds, saves to DB, publishes FinalDecisionEvent
- `DecisionThresholds` — configuration record holding the threshold values
- `FinalDecision` — JPA entity

---

### 5.6 case-management-service

**Port:** 8086  
**Auth:** HTTP Basic — two roles: `ANALYST` (analyst1 / analyst1pass) and `ADMIN` (admin1 / admin1pass)

Receives `FinalDecisionEvent` messages and creates cases in PostgreSQL for REVIEW and BLOCK outcomes. Provides a REST API for analysts to list cases and submit verdicts.

**Case lifecycle:**

```
FinalDecisionEvent (REVIEW or BLOCK)
        │
        ▼
  FraudCase created with status = OPEN
        │
  Analyst reviews via GET /cases
        │
  Analyst submits verdict via POST /cases/{id}/verdict
        │
        ├── verdict = CONFIRMED_FRAUD
        │       → Case status = CLOSED
        │       → FeedbackEvent published to feedback-events
        │       → feedback-service updates Redis reputation
        │
        └── verdict = FALSE_POSITIVE
                → Case status = CLOSED
                → No feedback event (reputation unchanged)
```

**Audit trail:**

Every verdict submission is recorded in the `audit_log` table with the analyst username, case ID, transaction ID, verdict, and timestamp.

**Key classes:**
- `FinalDecisionConsumer` — Kafka listener, creates FraudCase records
- `CaseController` — REST endpoints for listing cases and submitting verdicts
- `CaseService` — business logic, audit logging, feedback event publishing
- `FraudCase` — JPA entity
- `AuditLog` — JPA entity

---

### 5.7 feedback-service

**Port:** 8087

Closes the learning loop. When an analyst confirms fraud, this service writes a `HIGH_RISK` flag into Redis against the user and device. The next transaction from that user or device will be enriched with elevated risk signals, making it more likely to be flagged.

**What it writes to Redis:**

| Key | Value |
|---|---|
| `rep:user:{userId}` | hash field `flags` = `HIGH_RISK` |
| `rep:device:{deviceId}` | hash field `flags` = `HIGH_RISK` |

These keys use the same schema as `enrichment-service`'s reputation cache, so the enrichment service reads them automatically on the next transaction.

TTL is configurable via `feedback.reputation-ttl-seconds` (default: 86400 = 24 hours).

**Key classes:**
- `FeedbackConsumer` — Kafka listener, only processes `CONFIRMED_FRAUD` verdicts
- `ReputationUpdateService` — writes HIGH_RISK flags to Redis

---

### 5.8 ml-inference-service

**Port:** 8088 (runs in Docker)

A stub ML model server. In production this would be replaced by a real model server such as AWS SageMaker, TorchServe, or ONNX Runtime.

**Current behaviour:**

Returns a random `fraudProbability` between 0.0 and 1.0 using `SecureRandom`. Also returns a `modelVersion` field (`stub-1.0`).

**Request:**
```json
POST /infer
{
  "userId": "user-123",
  "amount": 250.00,
  "deviceId": "device-789",
  "ipAddress": "192.168.1.1"
}
```

**Response:**
```json
{
  "fraudProbability": 0.73,
  "modelVersion": "stub-1.0"
}
```

To replace with a real model, implement the same `/infer` contract and update `ml.inference.url` in `scoring-service`'s config.

---

## 6. Kafka Topics

| Topic | Producer | Consumer | Partitions | Key |
|---|---|---|---|---|
| `payment-events` | ingestion-service | scoring-service | 6 | userId |
| `decision-events` | scoring-service | decision-service | 6 | userId |
| `final-decisions` | decision-service | case-management-service | 6 | transactionId |
| `feedback-events` | case-management-service | feedback-service | 6 | userId |
| `payment-events-dlq` | scoring-service | (manual inspection) | 6 | — |

**Why userId as the Kafka key?**

Kafka guarantees ordering within a partition. By using `userId` as the key, all transactions from the same user always land on the same partition and are processed in order. This prevents race conditions where two transactions from the same user are processed simultaneously by different threads.

**Why 6 partitions?**

`scoring-service` runs with `concurrency = 6`, meaning one listener thread per partition. With 6 partitions, you can scale to a maximum of 6 `scoring-service` instances before needing to increase the partition count. Partition count can only be increased, never decreased.

---

## 7. Database Schema

### rules table (rules-service)
```sql
CREATE TABLE rules (
  id                   BIGSERIAL PRIMARY KEY,
  name                 TEXT NOT NULL,
  condition_expression TEXT NOT NULL,
  weight               INT NOT NULL,
  active               BOOLEAN NOT NULL DEFAULT TRUE,
  version              INT NOT NULL DEFAULT 1,
  created_at           TIMESTAMPTZ NOT NULL,
  updated_at           TIMESTAMPTZ NOT NULL
);
```

### rule_audit_log table (rules-service)
```sql
CREATE TABLE rule_audit_log (
  id           BIGSERIAL PRIMARY KEY,
  rule_id      BIGINT NOT NULL,
  rule_name    TEXT NOT NULL,
  action       VARCHAR(20) NOT NULL,  -- CREATE | UPDATE | ACTIVATE | DEACTIVATE
  changed_by   TEXT NOT NULL,
  changed_at   TIMESTAMPTZ NOT NULL,
  before_state TEXT,
  after_state  TEXT
);
```

### transactions table (scoring-service)
```sql
CREATE TABLE transactions (
  idempotency_key VARCHAR(36) PRIMARY KEY,
  user_id         TEXT NOT NULL,
  amount          NUMERIC NOT NULL,
  merchant_id     TEXT,
  device_id       TEXT,
  ip_address      TEXT,
  card_last4      TEXT,
  card_country    TEXT,
  ip_country      TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

### case_decisions table (scoring-service)
```sql
CREATE TABLE case_decisions (
  id              BIGSERIAL PRIMARY KEY,
  transaction_id  VARCHAR(36) NOT NULL,
  user_id         TEXT NOT NULL,
  status          TEXT NOT NULL,  -- ALLOW | REVIEW | BLOCK
  risk_score      INT NOT NULL,
  rules_triggered TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

### final_decisions table (decision-service)
```sql
CREATE TABLE final_decisions (
  transaction_id  VARCHAR(36) PRIMARY KEY,
  user_id         TEXT NOT NULL,
  device_id       TEXT,
  outcome         TEXT NOT NULL,  -- APPROVE | REVIEW | BLOCK
  fraud_score     DOUBLE PRECISION NOT NULL,
  rule_triggered  TEXT,
  decided_at      TIMESTAMPTZ
);
```

### fraud_cases table (case-management-service)
```sql
CREATE TABLE fraud_cases (
  id              BIGSERIAL PRIMARY KEY,
  transaction_id  VARCHAR(36) NOT NULL,
  user_id         TEXT NOT NULL,
  device_id       TEXT,
  outcome         TEXT NOT NULL,  -- REVIEW | BLOCK
  fraud_score     DOUBLE PRECISION,
  status          TEXT NOT NULL DEFAULT 'OPEN',  -- OPEN | CLOSED
  verdict         TEXT,           -- CONFIRMED_FRAUD | FALSE_POSITIVE
  reviewed_by     TEXT,
  reviewed_at     TIMESTAMPTZ,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

### audit_log table (case-management-service)
```sql
CREATE TABLE audit_log (
  id              BIGSERIAL PRIMARY KEY,
  case_id         BIGINT NOT NULL,
  transaction_id  VARCHAR(36) NOT NULL,
  analyst         TEXT NOT NULL,
  verdict         TEXT NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

All tables are created automatically by Hibernate (`ddl-auto: update`) on first startup.

---

## 8. Redis Data Structures

### Velocity counter
```
Key:    velocity:{userId}
Type:   Sorted Set (ZSET)
Score:  epoch-milliseconds of each transaction
Member: epoch-milliseconds string (unique per event)
TTL:    velocityWindowSeconds + 10 seconds
```

Example: `velocity:user-123` → ZSET with entries for each transaction timestamp in the last 5 minutes.

### Device reputation cache
```
Key:    rep:device:{deviceId}
Type:   Hash
Field:  flags
Value:  comma-separated flag strings, e.g. "EMULATOR,ROOTED"
TTL:    3600 seconds (1 hour) — extended to 86400s after CONFIRMED_FRAUD
```

### IP reputation cache
```
Key:    rep:ip:{ipAddress}
Type:   Hash
Field:  flags
Value:  comma-separated flag strings, e.g. "TOR_EXIT,DATACENTER"
TTL:    3600 seconds (1 hour)
```

### User reputation (written by feedback-service)
```
Key:    rep:user:{userId}
Type:   Hash
Field:  flags
Value:  "HIGH_RISK" (appended to existing flags if any)
TTL:    86400 seconds (24 hours, configurable)
```

---

## 9. Fraud Scoring Logic

### Step 1 — Rules score

The `FraudRulesEngine` evaluates five built-in rules. Each triggered rule adds 20 points:

| Rule | Condition | Points |
|---|---|---|
| HIGH_AMOUNT | amount > $10,000 | +20 |
| HIGH_VELOCITY | velocityCount >= 5 in 5 min | +20 |
| GEO_MISMATCH | IP country ≠ card country | +20 |
| RISKY_DEVICE | deviceRiskFlags not empty | +20 |
| RISKY_IP | ipRiskFlags not empty | +20 |

Maximum rules score = 100 (all 5 rules triggered).

### Step 2 — ML score

`ml-inference-service` returns a `fraudProbability` between 0.0 and 1.0. This is multiplied by 100 to get a 0–100 ML score.

### Step 3 — Blended final score

```
finalScore = 0.6 × rulesScore + 0.4 × mlScore
```

If ML is unavailable (circuit breaker open or timeout):
```
finalScore = rulesScore
```

### Step 4 — Decision thresholds

```
finalScore < 30   →  APPROVE
finalScore 30–70  →  REVIEW
finalScore > 70   →  BLOCK
```

### Example

A transaction with:
- Amount = $15,000 (HIGH_AMOUNT triggered, +20)
- Velocity = 6 in 5 min (HIGH_VELOCITY triggered, +20)
- IP country = RU, card country = US (GEO_MISMATCH triggered, +20)
- Clean device and IP

Rules score = 60. ML score = 80 (probability 0.80).

```
finalScore = 0.6 × 60 + 0.4 × 80 = 36 + 32 = 68 → REVIEW
```

---

## 10. Resilience and Fault Tolerance

### Idempotency

Every service that writes to a database checks for duplicate processing:
- `scoring-service` checks `transactions` table by `idempotencyKey` before processing
- `decision-service` checks `final_decisions` table by `transactionId` before processing

If a message is redelivered (e.g. after a crash), the duplicate is silently skipped.

### Kafka producer idempotency

All Kafka producers are configured with:
```
enable.idempotence=true
acks=all
max.in.flight.requests.per.connection=5
retries=2147483647
```

The broker assigns each producer a unique epoch + sequence number and deduplicates retried produce requests. A network blip never causes a duplicate message on the topic.

### Circuit breakers (Resilience4j)

| Circuit breaker | Protects | Failure threshold | Open duration | Fallback |
|---|---|---|---|---|
| `ml-inference` | ML model HTTP call | 50% over 10 calls | 10 seconds | Rules-only scoring |
| `rules-service` | Rules-service HTTP call | 50% over 10 calls | 10 seconds | Embedded FraudRulesEngine |
| Redis (via RedisGuard) | All Redis calls | 50% over 20 calls | 10 seconds | Neutral enrichment values |

### Dead letter queue

Messages that fail processing after all retries are published to `payment-events-dlq` and stored in the `dlq_messages` table. This prevents a bad message from blocking the entire partition.

### Ordering guarantee

All messages for a given `userId` are routed to the same Kafka partition (via the message key). Kafka guarantees ordering within a partition. The `concurrency = 6` listener setting assigns one thread per partition, so no two threads ever race on the same user's events.

---

## 11. Security

### ingestion-service
No authentication required. The `/transactions` endpoint is public. In production, protect it with an API gateway or mTLS.

### rules-service
HTTP Basic authentication required for all `/rules/**` endpoints. Credentials are configured via:
```
RULES_ANALYST_USER=analyst
RULES_ANALYST_PASSWORD=analyst
```
The authenticated username is captured from the `Authentication` object and written to the audit log on every rule change.

### case-management-service
HTTP Basic authentication with two roles:

| Role | Username | Can do |
|---|---|---|
| ANALYST | analyst1 | List cases, submit verdicts |
| ADMIN | admin1 | List cases, submit verdicts |

Endpoints are protected with `@PreAuthorize("hasAnyRole('ANALYST','ADMIN')")`.

### Passwords in config
All credentials are injected via environment variables. The `.env` file is gitignored and never committed. See `.env.example` for the full list of variables.

---

## 12. Observability

Every service is instrumented with:
- **Micrometer** metrics exposed at `/actuator/prometheus`
- **OpenTelemetry** traces sent to Jaeger at `http://localhost:4317` (OTLP gRPC)
- **Spring Boot Actuator** health endpoint at `/actuator/health`

### Metrics (Prometheus + Grafana)

| Metric | Service | Description |
|---|---|---|
| `scoring_latency_seconds` | scoring-service | Histogram of end-to-end scoring time |
| `resilience4j_circuitbreaker_state` | scoring-service | Circuit breaker state (CLOSED/OPEN/HALF_OPEN) |
| `kafka_consumer_lag` | all consumers | How far behind each consumer group is |
| `jvm_memory_used_bytes` | all services | JVM heap usage |
| `http_server_requests_seconds` | all services | HTTP request latency by endpoint |

Access Grafana at `http://localhost:3000` (admin / admin).

### Distributed tracing (Jaeger)

Every request gets a trace ID that follows it across all services. You can search by trace ID in Jaeger at `http://localhost:16686` to see the full timeline of a single transaction — from ingestion through enrichment, scoring, and decision.

Sampling is set to 100% (`probability: 1.0`) for local development. Reduce this in production.

### Log files

All service logs are written to `logs\` in the project root:

```
logs\
  ingestion.log
  enrichment.log
  rules.log
  scoring.log
  decision.log
  case-management.log
  feedback.log
```

---

## 13. API Reference

### ingestion-service (port 8081)

#### POST /transactions
Submit a payment transaction for fraud analysis.

**Request body:**
```json
{
  "userId":      "user-123",
  "amount":      250.00,
  "merchantId":  "merchant-456",
  "deviceId":    "device-789",
  "ipAddress":   "192.168.1.1",
  "timestamp":   "2026-09-24T08:00:00Z",
  "cardLast4":   "4242",
  "cardCountry": "US"
}
```

**Response — 202 Accepted:**
```json
{
  "idempotencyKey": "550e8400-e29b-41d4-a716-446655440000",
  "status": "ACCEPTED"
}
```

**Validation errors — 400 Bad Request** if any required field is missing or invalid.

---

### rules-service (port 8083) — requires Basic Auth

#### GET /rules
List all rules (active and inactive).

#### POST /rules
Create a new fraud rule.

**Request body:**
```json
{
  "name": "High amount",
  "conditionExpression": "amount > 10000",
  "weight": 40
}
```

#### PATCH /rules/{id}
Update an existing rule. All fields are optional.

```json
{
  "conditionExpression": "amount > 15000",
  "weight": 50,
  "active": false
}
```

#### GET /rules/{id}/audit
Get the full change history for a rule.

#### POST /rules/evaluate
Evaluate all active rules against a set of transaction fields.

**Request body:**
```json
{
  "amount": 15000,
  "velocityCount": 6,
  "deviceRiskFlags": ["EMULATOR"],
  "ipRiskFlags": []
}
```

**Response:**
```json
{
  "triggeredRules": ["HIGH_AMOUNT", "HIGH_VELOCITY", "RISKY_DEVICE"],
  "totalWeight": 120
}
```

---

### case-management-service (port 8086) — requires Basic Auth

#### GET /cases
List fraud cases. Optionally filter by status.

```
GET /cases?status=OPEN&page=0&size=20&sort=createdAt,desc
```

#### POST /cases/{id}/verdict
Submit an analyst verdict for a case.

```json
{
  "verdict": "CONFIRMED_FRAUD"
}
```

Valid verdicts: `CONFIRMED_FRAUD`, `FALSE_POSITIVE`

---

### All services

#### GET /actuator/health
Returns service health status.

```json
{ "status": "UP" }
```

#### GET /actuator/prometheus
Returns Prometheus metrics in text format.

---

## 14. Configuration Reference

All environment variables with their defaults:

| Variable | Default | Used by |
|---|---|---|
| `POSTGRES_DB` | `frauddb` | Docker, Postgres |
| `POSTGRES_USER` | `fraud` | Docker, Postgres |
| `POSTGRES_PASSWORD` | `fraud` | Docker, Postgres |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/frauddb` | rules, scoring, decision, case-management |
| `SPRING_DATASOURCE_USERNAME` | `fraud` | same |
| `SPRING_DATASOURCE_PASSWORD` | `fraud` | same |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | all services |
| `RULES_ANALYST_USER` | `analyst` | rules-service |
| `RULES_ANALYST_PASSWORD` | `analyst` | rules-service |
| `CASE_ANALYST_USER` | `analyst1` | case-management-service |
| `CASE_ANALYST_PASSWORD` | `analyst1pass` | case-management-service |
| `CASE_ADMIN_USER` | `admin1` | case-management-service |
| `CASE_ADMIN_PASSWORD` | `admin1pass` | case-management-service |
| `GRAFANA_ADMIN_PASSWORD` | `admin` | Grafana |

Application-level config (in each service's `application.yml`):

| Property | Default | Service | Description |
|---|---|---|---|
| `fraud.rules.amount-threshold` | `10000` | scoring | HIGH_AMOUNT rule threshold |
| `fraud.rules.velocity-max-count` | `5` | scoring | HIGH_VELOCITY rule threshold |
| `fraud.rules.velocity-window-minutes` | `5` | scoring | Velocity window duration |
| `enrichment.velocity-window-seconds` | `300` | enrichment | Redis ZSET window |
| `enrichment.reputation-ttl-seconds` | `3600` | enrichment | Reputation cache TTL |
| `enrichment.redis-timeout-ms` | `5` | enrichment | RedisGuard hard timeout |
| `decision.thresholds.approve-below` | `30` | decision | Score below this = APPROVE |
| `decision.thresholds.block-above` | `70` | decision | Score above this = BLOCK |
| `ml.inference.url` | `http://localhost:8088` | scoring | ML model server URL |
| `rules.service.url` | `http://localhost:8083` | scoring | Rules-service URL |
| `feedback.risk-bump` | `0.5` | feedback | Risk score increment on fraud |
| `feedback.reputation-ttl-seconds` | `86400` | feedback | HIGH_RISK flag TTL (24h) |

---

## 15. Running Locally

### Prerequisites
- Docker Desktop
- Java 17+
- Maven 3.9+

### First-time setup

```cmd
cd d:\Projects\fraud-platform
copy .env.example .env
```

### Start everything

```cmd
.\start.bat
```

This script:
1. Creates the `logs\` directory
2. Runs `docker compose up -d` to start Kafka, Postgres, Redis, ml-inference, Jaeger, Prometheus, Grafana
3. Polls Kafka until it is healthy
4. Creates all 5 Kafka topics with `--if-not-exists`
5. Launches all 7 Spring Boot JARs in separate windows
6. Waits 45 seconds then checks all health endpoints

### Expected output

```
-- Health checks --
  [ OK ] ingestion-service       :8081
  [ OK ] enrichment-service      :8082
  [ OK ] rules-service           :8083
  [ OK ] scoring-service         :8084
  [ OK ] decision-service        :8085
  [ OK ] case-management-service :8086
  [ OK ] feedback-service        :8087
```

### Send a test transaction

```bash
curl -X POST http://localhost:8081/transactions \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "user-123",
    "amount": 250.00,
    "merchantId": "merchant-456",
    "deviceId": "device-789",
    "ipAddress": "192.168.1.1",
    "timestamp": "2026-09-24T08:00:00Z",
    "cardLast4": "4242",
    "cardCountry": "US"
  }'
```

### Add a fraud rule

```bash
curl -X POST http://localhost:8083/rules \
  -u analyst:analyst \
  -H "Content-Type: application/json" \
  -d '{
    "name": "High amount",
    "conditionExpression": "amount > 10000",
    "weight": 40
  }'
```

### Rebuild after code changes

```bash
mvn clean install -DskipTests
.\start.bat
```

---

## 16. Kubernetes and Production

The `k8s/` directory contains a Helm chart that deploys the full platform.

### Autoscaling

- **KEDA** scales `scoring-service` based on Kafka consumer lag on the `payment-events` topic
- **Kubernetes HPA** scales all services based on CPU utilisation

### Managed AWS infrastructure

Set `kafka.useManaged=true` in `k8s/values.yaml` to use **Amazon MSK** instead of the in-cluster Kafka broker.

For the database, replace the in-cluster Postgres with **Amazon RDS for PostgreSQL** by updating `SPRING_DATASOURCE_URL`.

For Redis, replace with **Amazon ElastiCache for Redis** by updating `spring.data.redis.host`.

### Replacing the ML model

The `ml-inference-service` is a stub. To use a real model in production:
1. Deploy your model to AWS SageMaker, TorchServe, or any HTTP server
2. Implement the same `/infer` contract (accepts features JSON, returns `fraudProbability`)
3. Update `ml.inference.url` in `scoring-service`'s config to point to the new endpoint
4. The circuit breaker and fallback logic remain unchanged
