# Payment Idempotency Framework

A production-ready framework for building idempotent payment processors with state machine discipline. Prevents duplicate payments through architectural enforcement.

## Overview

This framework ensures that payment messages are processed **exactly once**, even in the face of:
- Message queue redelivery (MQ connection drops)
- Application crashes and restarts
- Downstream system failures
- Network timeouts

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                                                                     │
│   Message Queue          Your Handler            Downstream        │
│   (IBM MQ/Kafka)         (30 lines)              (Ledger/API)      │
│                                                                     │
│   ┌───────┐           ┌─────────────┐           ┌───────────┐      │
│   │       │           │             │           │           │      │
│   │ Queue ├──────────►│  Framework  ├──────────►│  Ledger   │      │
│   │       │           │  handles:   │           │           │      │
│   └───────┘           │  ✓ Idem.    │           └───────────┘      │
│                       │  ✓ State    │                              │
│                       │  ✓ Outbox   │                              │
│                       │  ✓ Retry    │                              │
│                       │  ✓ Metrics  │                              │
│                       └─────────────┘                              │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

## Quick Start

### 1. Add Dependencies

```xml
<!-- Starter (always required) -->
<dependency>
    <groupId>com.bank.payments</groupId>
    <artifactId>payment-framework-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

<!-- Choose ONE persistence adapter -->
<dependency>
    <groupId>com.bank.payments</groupId>
    <artifactId>payment-framework-jpa</artifactId>        <!-- Oracle / any JDBC -->
    <version>1.0.0-SNAPSHOT</version>
</dependency>
<!-- OR -->
<dependency>
    <groupId>com.bank.payments</groupId>
    <artifactId>payment-framework-mongodb</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

<!-- Choose ONE messaging adapter -->
<dependency>
    <groupId>com.bank.payments</groupId>
    <artifactId>payment-framework-ibmmq</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
<!-- OR -->
<dependency>
    <groupId>com.bank.payments</groupId>
    <artifactId>payment-framework-kafka</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 2. Create Database Tables

- **Oracle/JPA:** run `database/oracle/V1__create_payment_tables.sql`
- **MongoDB:** collections and indexes are created automatically

### 3. Implement Your Consumer

**IBM MQ:**
```java
@Component
public class PaymentEventMqConsumer extends JmsPaymentConsumer<PaymentEvent> {

    public PaymentEventMqConsumer(IdempotencyService idempotencyService,
                                  ObjectMapper objectMapper,
                                  MeterRegistry meterRegistry) {
        super(idempotencyService, objectMapper, meterRegistry);
    }

    @Override
    @JmsListener(destination = "PAYMENT.INBOUND.QUEUE")
    public void onMessage(Message message) {
        super.onMessage(message);   // framework handles everything else
    }

    @Override
    protected String extractIdempotencyKey(Message message, PaymentEvent payload) {
        return payload.getTransactionId();
    }

    @Override
    protected String getDestination() { return "CENTRAL_LEDGER"; }

    @Override
    public Class<PaymentEvent> getPayloadType() { return PaymentEvent.class; }

    @Override
    public OutboxPayload processPayment(PaymentEvent event) {
        LedgerRequest request = transform(event);
        return OutboxPayload.restPost("https://ledger.internal/api/v1/transactions", request);
    }
}
```

**Kafka:**
```java
@Component
public class PaymentEventKafkaConsumer extends KafkaPaymentConsumer<PaymentEvent> {

    public PaymentEventKafkaConsumer(IdempotencyService idempotencyService,
                                     ObjectMapper objectMapper,
                                     MeterRegistry meterRegistry) {
        super(idempotencyService, objectMapper, meterRegistry);
    }

    @Override
    @KafkaListener(topics = "payments.inbound", groupId = "payment-processor")
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        super.consume(record, ack);   // framework handles everything else
    }

    @Override
    protected String extractIdempotencyKey(ConsumerRecord<String, String> record, PaymentEvent payload) {
        return payload.getTransactionId();
    }

    @Override
    protected String getDestination() { return "CENTRAL_LEDGER"; }

    @Override
    public Class<PaymentEvent> getPayloadType() { return PaymentEvent.class; }

    @Override
    public OutboxPayload processPayment(PaymentEvent event) {
        LedgerRequest request = transform(event);
        return OutboxPayload.restPost("https://ledger.internal/api/v1/transactions", request);
    }
}
```

### 4. Configure

```yaml
payment:
  framework:
    enabled: true
    outbox:
      enabled: true
      max-retries: 5
      retry-base-delay-seconds: 30
```

## What the Framework Does

| Step | What Happens | You Write |
|------|--------------|-----------|
| 1. Message arrives | Framework extracts idempotency key | SpEL expression |
| 2. Idempotency check | Framework checks processed_events table | Nothing |
| 3. Process message | Framework calls your handler | Business logic |
| 4. Persist state | Framework saves to DB atomically | Nothing |
| 5. Acknowledge MQ | Framework ACKs after DB commit | Nothing |
| 6. Deliver downstream | OutboxProcessor delivers async | Endpoint config |
| 7. Handle failures | Framework retries with backoff | Nothing |
| 8. Emit metrics | Framework exports to Prometheus | Nothing |

## Modules

| Module | Description | Status |
|--------|-------------|--------|
| `payment-framework-core` | Interfaces, domain models, state machine, `IdempotencyService`, `OutboxProcessor` | Complete |
| `payment-framework-jpa` | JPA/Oracle persistence adapter | Complete |
| `payment-framework-mongodb` | MongoDB persistence adapter | Complete |
| `payment-framework-kafka` | Kafka consumer adapter (`KafkaPaymentConsumer<T>`) | Complete |
| `payment-framework-ibmmq` | IBM MQ (JMS) consumer adapter (`JmsPaymentConsumer<T>`) | Complete |
| `payment-framework-starter` | Spring Boot auto-configuration | Complete |

Auto-configuration activates when both a `ProcessedEventRepository` bean **and** an `OutboxRepository` bean are present on the classpath — provided automatically by the JPA or MongoDB adapter. No `@Import` is needed.

## State Machine

```
RECEIVED → PROCESSING → SENDING → COMPLETED  (terminal)
                   ↘         ↘
                   FAILED ←──┘
                     ↓
                  SENDING  (retry)
```

All transitions go through `PaymentState.transitionTo()`. Direct field assignment is a **BLOCKER**.

## Non-Negotiable Invariants

Violating any of these is a **BLOCKER**:

1. `processPayment()` and `OutboxEntry` creation commit in the **same `@Transactional` boundary** — no `@Async`, no thread handoff.
2. All `PaymentState` transitions go through `PaymentState.transitionTo()` — never assign `.state` directly.
3. `tryLock()` is a **single atomic conditional UPDATE** (CAS) — never read-then-write.
4. `releaseLock()` includes `AND locked_by = :lockedBy` — prevents cross-node lock release.
5. Every entity has a `@Version Long version` field — never remove it, never set it manually.
6. All mutating repository methods are `@Transactional`.
7. **Retryable:** 408, 429, 5xx. **Permanent:** all other 4xx. Treating 400 as retryable causes infinite loops.
8. Backoff: `delay = min(baseDelay × 2^retryCount, maxDelay)`. After `maxRetries`, status → `FAILED`.

## Running Tests

```bash
# All modules
mvn test

# Single module
mvn test -pl payment-framework-jpa
mvn test -pl payment-framework-mongodb
mvn test -pl payment-framework-kafka
mvn test -pl payment-framework-ibmmq
```

No Docker required. Tests use H2 (`@DataJpaTest`), Flapdoodle embedded MongoDB (`@DataMongoTest`), `@EmbeddedKafka`, and Mockito for IBM MQ. See [`docs/TESTING_GUIDE.md`](docs/TESTING_GUIDE.md) for the full guide.

## Developer Guides

| Task | Guide |
|------|-------|
| Add a new persistence or messaging adapter | [`docs/HOW_TO_ADD_ADAPTER.md`](docs/HOW_TO_ADD_ADAPTER.md) |
| Add a new Maven module | [`docs/HOW_TO_ADD_MODULE.md`](docs/HOW_TO_ADD_MODULE.md) |
| Contribute to the framework | [`docs/CONTRIBUTING.md`](docs/CONTRIBUTING.md) |
| Run and write tests | [`docs/TESTING_GUIDE.md`](docs/TESTING_GUIDE.md) |

### Claude Code Skills

If you use [Claude Code](https://claude.ai/code), invoke these skills **before** implementing any of the tasks below — they encode invariants and patterns that must be followed exactly:

| Task | Skill |
|------|-------|
| Add a new adapter | `/add-adapter` |
| Add a new Maven module | `/add-module` |
| Review a PR or code change | `/review-framework` |
| Write tests for any component | `/test-payment` |
| Write or review any Java code | `/java-coding-standards` |

## Metrics (Prometheus)

The framework exports these metrics:

| Metric | Description |
|--------|-------------|
| `payment.idempotency.duplicates` | Duplicate messages detected |
| `payment.idempotency.processed` | Events successfully processed |
| `payment.outbox.pending` | Pending outbox entries |
| `payment.outbox.delivery.success` | Successful deliveries |
| `payment.outbox.delivery.failure` | Failed deliveries |
| `payment.outbox.delivery.time` | Delivery duration |

## Configuration Reference

```yaml
payment:
  framework:
    enabled: true                    # Master switch
    outbox:
      enabled: true                  # Enable embedded processor
      poll-interval-ms: 1000         # Polling interval
      batch-size: 10                 # Entries per poll
      worker-threads: 4              # Parallel delivery threads
      max-retries: 5                 # Max retry attempts
      retry-base-delay-seconds: 30   # Base backoff delay
      retry-max-delay-seconds: 3600  # Max backoff delay
      lock-duration-seconds: 60      # Lock hold time
      delivery-timeout-seconds: 30   # HTTP timeout
```

## Handler Callbacks

```java
@Override
public void onDuplicateDetected(PaymentEvent event, String key) {
    // Called when duplicate detected - log, metrics, etc.
}

@Override
public void onPermanentFailure(PaymentEvent event, String key, String error) {
    // Called when max retries exceeded - alert, ticket, etc.
}

@Override
public void onDeliverySuccess(PaymentEvent event, String key) {
    // Called on successful delivery - audit, notification, etc.
}
```

## FAQ

### How is this different from just adding a unique constraint?

A unique constraint is a last line of defense, but it:
- Doesn't prevent the processing work from happening
- Doesn't handle retries
- Doesn't give you visibility into state

This framework provides **prevention** (idempotency check), **resilience** (outbox + retry), and **observability** (state + metrics).

### Can I run the outbox processor separately?

Yes. Set `payment.framework.outbox.enabled=false` in your consumer app and run a dedicated processor service with it enabled.

### Does this work with MongoDB?

Yes. Add `payment-framework-mongodb` instead of `payment-framework-jpa`.

### What if my downstream doesn't support idempotency?

The framework handles this by:
1. Never calling downstream until state is persisted
2. Using outbox pattern to decouple receipt from delivery
3. Retrying with backoff on failure

If downstream creates a duplicate on retry, that's a downstream problem - but you'll have clear audit trail to identify and fix it.

## Support

- Internal Wiki: [link]
- Slack: #payment-framework
- Issues: [internal JIRA]

## License

Internal use only. Property of [Bank Name].
