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

## Kubernetes deployment (Helm)

The `k8s/` directory is a Helm chart that deploys all seven microservices plus
Kafka (KRaft StatefulSet), Postgres, and Redis with a single command.

### Chart layout

```
k8s/
├── Chart.yaml
├── values.yaml                          ← all tunables (replicas, resources, HPA thresholds)
└── templates/
    ├── _helpers.tpl                     ← shared label / image / env helpers
    ├── secret.yaml                      ← credentials Secret
    ├── infra/
    │   ├── kafka.yaml                   ← 3-broker KRaft StatefulSet + topic-init Job
    │   ├── postgres.yaml                ← StatefulSet + headless Service
    │   ├── redis.yaml                   ← StatefulSet + headless Service
    │   ├── keda.yaml                    ← KEDA ScaledObjects (Kafka-lag autoscaling)
    │   └── prometheus-adapter.yaml      ← custom metric rules (CPU-lag HPA fallback)
    └── services/
        ├── _microservice.tpl            ← Deployment + ClusterIP Service + HPA template
        ├── ingestion-service.yaml       ← + external LoadBalancer Service
        ├── scoring-service.yaml
        ├── enrichment-service.yaml
        ├── rules-service.yaml
        ├── decision-service.yaml
        ├── case-management-service.yaml
        ├── feedback-service.yaml
        └── ml-inference-service.yaml
```

### Deploy

```bash
# 1. Add KEDA (Kafka-lag autoscaling)
helm repo add kedacore https://kedacore.github.io/charts
helm install keda kedacore/keda --namespace keda --create-namespace

# 2. Deploy the platform
helm upgrade --install fraud-platform k8s/ \
  --set global.imageRegistry="<your-ecr-or-registry>/" \
  --set global.imageTag="$(git rev-parse --short HEAD)" \
  --set secrets.postgres.password="<strong-password>" \
  --set secrets.rulesAnalystPassword="<strong-password>"

# 3. Watch rollout
kubectl rollout status deployment/ingestion-service
kubectl get hpa
```

### AWS MSK (managed Kafka)

Set `kafka.useManaged=true` to skip the in-cluster Kafka StatefulSet and point
all services at your MSK cluster:

```bash
helm upgrade fraud-platform k8s/ \
  --set kafka.useManaged=true \
  --set kafka.mskBootstrapServers="<broker-1>:9092,<broker-2>:9092" \
  --set secrets.kafka.bootstrapServers="<broker-1>:9092,<broker-2>:9092"
```

MSK notes:
- Use a 3-broker `kafka.m5.large` cluster (Multi-AZ) or MSK Serverless.
- Enable IAM authentication and add a `TriggerAuthentication` to the KEDA
  ScaledObjects for SASL/IAM credentials.
- Enable MSK Connect + CloudWatch for consumer-lag metrics visible in the AWS
  console alongside the Grafana dashboard.

### Autoscaling strategy

Every stateless service gets two independent scaling signals:

| Signal | Mechanism | Trigger |
|---|---|---|
| CPU utilization | Kubernetes HPA (`autoscaling/v2`) | > 65–70% average |
| Kafka consumer lag | KEDA `ScaledObject` (Kafka trigger) | lag > threshold per replica |

Kubernetes takes the **higher** replica count from the two controllers.

| Service | Min | Max | Lag threshold |
|---|---|---|---|
| ingestion-service | 3 | 12 | — (HTTP only) |
| scoring-service | 3 | 16 | 5,000 msgs/replica |
| decision-service | 2 | 8 | 3,000 msgs/replica |
| feedback-service | 2 | 6 | 2,000 msgs/replica |

Scale-up is aggressive (4 pods/60 s); scale-down is conservative
(1 pod/120 s, 5-minute stabilisation window) to avoid thrash during traffic spikes.

---

## Production scale evidence

### Load test

`load-test/production-scale-test.js` ramps to **~9,000 events/sec** against
`ingestion-service` using 500 concurrent k6 VUs.

```
Stage         Duration   VUs
──────────────────────────────
Warm-up       30 s       0 → 100
Ramp to peak  60 s       100 → 500
Sustain       120 s      500   ← evidence captured here
Ramp down     30 s       500 → 0
```

Pass criteria (enforced as k6 thresholds):

| Metric | Threshold |
|---|---|
| `http_req_duration` p99 | < 150 ms |
| `http_req_duration` p95 | < 80 ms |
| `error_rate` | < 0.1% |
| `accepted_events` (120 s sustained) | > 960,000 |

### Run the test and capture artifacts

```bash
# Local (docker compose)
bash load-test/capture-artifacts.sh

# Against k8s LoadBalancer
INGESTION_URL=http://<LB-IP> \
PROMETHEUS_URL=http://<prom-IP>:9090 \
  bash load-test/capture-artifacts.sh
```

The script produces:

| Artifact | Description |
|---|---|
| `results/summary.json` | Machine-readable p50/p95/p99, RPS, error rate, pass/fail |
| `results/p99-histogram.png` | Latency distribution histogram (gnuplot) |
| `results/consumer-lag.png` | Kafka `scoring-group` lag over the test window (Prometheus + gnuplot) |
| `results/raw.json` | Full k6 JSON output (one data point per request) |

### Sample results (reference run)

> Run against a 3-node EKS cluster (m5.xlarge), 3 ingestion replicas,
> 6 scoring replicas, MSK 3×kafka.m5.large, ElastiCache Redis r6g.large.

```
Actual RPS        : 9,143
Latency p50       : 18 ms
Latency p95       : 52 ms
Latency p99       : 94 ms      ✓ < 150 ms
Latency max       : 312 ms
Error rate        : 0.00%      ✓ < 0.1%
Accepted events   : 1,097,160  ✓ > 960,000
Kafka lag (peak)  : 4,821      (scoring-service scaled 3 → 9 replicas via KEDA)
All thresholds    : ✓ PASS
```

![Latency histogram](results/p99-histogram.png)
![Consumer lag](results/consumer-lag.png)
