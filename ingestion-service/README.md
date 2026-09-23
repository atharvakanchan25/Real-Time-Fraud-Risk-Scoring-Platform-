# ingestion-service — MVP Fraud Scoring

Single synchronous REST service that accepts transactions, applies fraud rules in-process,
persists everything to Postgres, and returns an instant decision.

## Fraud rules

| Rule | Trigger | Score contribution |
|---|---|---|
| `HIGH_AMOUNT` | `amount > 10 000` | +35 |
| `HIGH_VELOCITY` | ≥ 5 transactions from same `userId` in last 5 min | +35 |
| `GEO_MISMATCH` | IP-derived country ≠ card issuing country | +35 |

Final score is capped at 100. Decision thresholds:

| Score | Decision |
|---|---|
| < 30 | `ALLOW` |
| 30 – 70 | `REVIEW` |
| > 70 | `BLOCK` |

## API

### POST /transactions

```json
{
  "userId":      "u-123",
  "amount":      15000.00,
  "merchantId":  "m-456",
  "deviceId":    "d-789",
  "ipAddress":   "185.0.0.1",
  "timestamp":   "2024-01-15T10:30:00Z",
  "cardLast4":   "4242",
  "cardCountry": "US"
}
```

Response `201 Created`:

```json
{
  "transactionId": "3fa85f64-...",
  "decision":      "BLOCK",
  "riskScore":     70,
  "rulesTriggered": ["HIGH_AMOUNT", "GEO_MISMATCH"]
}
```

### GET /cases?status=REVIEW

Returns all `case_decisions` rows matching the given status (`ALLOW`, `REVIEW`, or `BLOCK`).
Defaults to `REVIEW`.

## How to run

### 1. Start Postgres

```bash
# from the repo root — starts only the postgres container
docker compose up -d postgres
```

Wait until healthy:

```bash
docker compose ps postgres
```

### 2. Run the service

```bash
cd ingestion-service
mvn spring-boot:run
```

Service starts on **http://localhost:8081**.

Health check: `GET http://localhost:8081/actuator/health`

### 3. Quick smoke test

```bash
curl -s -X POST http://localhost:8081/transactions \
  -H "Content-Type: application/json" \
  -d '{
    "userId":      "u-001",
    "amount":      500.00,
    "merchantId":  "m-001",
    "deviceId":    "d-001",
    "ipAddress":   "10.0.0.1",
    "timestamp":   "2024-01-15T10:00:00Z",
    "cardLast4":   "1234",
    "cardCountry": "US"
  }' | jq .

# Fetch REVIEW cases
curl -s http://localhost:8081/cases?status=REVIEW | jq .
```

## Running integration tests

Tests use Testcontainers — Docker must be running. No external Postgres needed.

```bash
cd ingestion-service
mvn verify
```

## Configuration

All thresholds are externalisable via `application.yml` or environment variables:

| Property | Default | Description |
|---|---|---|
| `fraud.rules.amount-threshold` | `10000` | High-amount rule threshold |
| `fraud.rules.velocity-max-count` | `5` | Max transactions before velocity fires |
| `fraud.rules.velocity-window-minutes` | `5` | Lookback window in minutes |
