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
<dependency>
    <groupId>com.dev.payments</groupId>
    <artifactId>payment-framework-starter</artifactId>
    <version>1.0.0</version>
</dependency>

<!-- Choose your database adapter -->
<dependency>
    <groupId>com.dev.payments</groupId>
    <artifactId>payment-framework-jpa</artifactId>
    <version>1.0.0</version>
</dependency>

<!-- Choose your messaging adapter -->
<dependency>
    <groupId>com.dev.payments</groupId>
    <artifactId>payment-framework-ibmmq</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 2. Create Database Tables

Run the migration script for your database:
- Oracle: `database/oracle/V1__create_payment_tables.sql`
- MongoDB: Collections created automatically

### 3. Implement Your Handler

```java
@IdempotentConsumer(
    idempotencyKey = "#{payload.transactionId}",
    destination = "CENTRAL_LEDGER"
)
@Component
public class PaymentEventHandler implements PaymentMessageHandler<PaymentEvent> {
    
    @Override
    public OutboxPayload processPayment(PaymentEvent event) {
        // Your business logic here - transform the event
        LedgerRequest request = transform(event);
        
        // Return what to send downstream
        return OutboxPayload.builder()
            .endpoint("https://ledger.internal/api/v1/transactions")
            .body(request)
            .build();
    }
}
```

That's it! The framework handles everything else.

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

| Module | Description |
|--------|-------------|
| `payment-framework-core` | Core abstractions, annotations, state machine |
| `payment-framework-jpa` | JPA/Oracle implementation |
| `payment-framework-mongodb` | MongoDB implementation |
| `payment-framework-ibmmq` | IBM MQ adapter |
| `payment-framework-kafka` | Kafka adapter |
| `payment-framework-starter` | Spring Boot auto-configuration |

## State Machine

Every payment event follows this lifecycle:

```
    [NEW MESSAGE]
          │
          ▼
     PROCESSING ──────► FAILED
          │                │
          ▼                │
      COMPLETED ◄──────────┘
                    (manual retry)
```

Invalid transitions (duplicates) are automatically rejected.

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
