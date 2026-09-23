# fraud-platform

Real-time fraud scoring platform built with Java 17, Spring Boot 3, and Apache Kafka.

## Architecture

Transactions flow through a pipeline of loosely-coupled microservices, each communicating via Kafka topics:

```
Client
  │
  ▼
ingestion-service  (port 8081)
  │  topic: raw-transactions
  ▼
enrichment-service (port 8082)   ◄── Redis (account/device context)
  │  topic: enriched-transactions
  ├──► rules-service   (port 8083)  ──► topic: rule-signals
  └──► scoring-service (port 8084)  ──► topic: fraud-scores
                                          │
                                          ▼
                                    decision-service (port 8085)
                                          │  topic: decisions
                                          ├──► case-management-service (port 8086) ──► Postgres
                                          └──► feedback-service        (port 8087) ──► Postgres
```

### Modules

| Module | Responsibility |
|---|---|
| `common` | Shared DTOs: `TransactionEvent`, `FeatureSet`, `DecisionEvent` |
| `ingestion-service` | Accepts raw transactions via REST, publishes to `raw-transactions` |
| `enrichment-service` | Consumes raw transactions, enriches from Redis, publishes `enriched-transactions` |
| `rules-service` | Evaluates deterministic fraud rules, publishes `rule-signals` |
| `scoring-service` | Computes ML fraud score from feature set, publishes `fraud-scores` |
| `decision-service` | Aggregates signals + scores, emits final `APPROVE / DECLINE / REVIEW` decision |
| `case-management-service` | Persists REVIEW/DECLINE cases to Postgres for analyst workflow |
| `feedback-service` | Accepts analyst verdicts, stores to Postgres for model retraining |

### Infrastructure

| Service | Image | Port |
|---|---|---|
| Kafka (KRaft, no Zookeeper) | `apache/kafka:3.7.0` | 9092 |
| PostgreSQL | `postgres:15-alpine` | 5432 |
| Redis | `redis:7-alpine` | 6379 |

## Prerequisites

- Docker Desktop (or Docker Engine + Compose plugin)
- Java 17+
- Maven 3.9+

## Running locally

**1. Start infrastructure**

```bash
docker compose up -d
```

Wait for all containers to be healthy:

```bash
docker compose ps
```

**2. Build all modules**

```bash
mvn clean install -DskipTests
```

**3. Start a service**

```bash
cd ingestion-service
mvn spring-boot:run
```

Each service exposes a health endpoint at `GET /health` and Spring Boot Actuator at `/actuator/health`.

## Kafka topics (create manually for local dev)

```bash
docker exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --topic raw-transactions --partitions 3 --replication-factor 1
docker exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --topic enriched-transactions --partitions 3 --replication-factor 1
docker exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --topic rule-signals --partitions 3 --replication-factor 1
docker exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --topic fraud-scores --partitions 3 --replication-factor 1
docker exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --topic decisions --partitions 3 --replication-factor 1
```

## Service ports

| Service | Port |
|---|---|
| ingestion-service | 8081 |
| enrichment-service | 8082 |
| rules-service | 8083 |
| scoring-service | 8084 |
| decision-service | 8085 |
| case-management-service | 8086 |
| feedback-service | 8087 |
