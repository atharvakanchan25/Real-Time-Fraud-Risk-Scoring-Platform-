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
