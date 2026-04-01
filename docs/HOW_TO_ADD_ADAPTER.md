# How to Add a New Persistence or Messaging Adapter

An adapter bridges the framework's core interfaces to a specific datastore (Oracle, MongoDB, Redis) or messaging technology (Kafka, IBM MQ). This guide covers persistence adapters in full detail; messaging adapters follow the same module scaffold but replace repository implementations with message listener infrastructure.

**Reference implementation:** `payment-framework-jpa` — always read this before writing a new adapter.

---

## Prerequisites

- Java 17, Maven 3.8+
- Spring Boot 3.x familiarity
- Understanding of the Outbox pattern (see [CONTRIBUTING.md](CONTRIBUTING.md))
- Familiarity with your target technology (Spring Data MongoDB, Lettuce, etc.)

---

## Overview

Every persistence adapter must implement two interfaces from `payment-framework-core`:

| Interface | Purpose |
|-----------|---------|
| `ProcessedEventRepository` | Tracks the idempotency lifecycle of each payment event (10 methods) |
| `OutboxRepository` | Manages the outbox entries for reliable async delivery (14 methods) |

The framework's `PaymentFrameworkAutoConfiguration` activates automatically once both repository beans are present on the classpath via `@ConditionalOnBean({ProcessedEventRepository.class, OutboxRepository.class})`. **Do not modify that class.**

---

## Step 1: Scaffold the Module

Follow [HOW_TO_ADD_MODULE.md](HOW_TO_ADD_MODULE.md) to create the Maven module first.

- Artifact ID: `payment-framework-{name}` (e.g. `payment-framework-redis`)
- Add your technology's Spring Boot starter as a dependency (e.g. `spring-boot-starter-data-mongodb`)
- Confirm `mvn validate` returns `BUILD SUCCESS` before continuing

---

## Step 2: Create the Package Layout

Under `src/main/java/com/bank/payments/framework/{name}/`, create:

```
{name}/
├── entity/       ← datastore-specific entity/document classes
├── repository/   ← adapter implementations + internal query interfaces
└── config/       ← only if the adapter needs its own connection factory or properties
```

---

## Step 3: Create Entity Classes

Create one entity class for `ProcessedEvent` and one for `OutboxEntry`. Read the JPA reference entities before starting:
- `payment-framework-jpa/src/main/java/com/bank/payments/framework/jpa/entity/ProcessedEventEntity.java`
- `payment-framework-jpa/src/main/java/com/bank/payments/framework/jpa/entity/OutboxEntryEntity.java`

### ProcessedEvent entity — required fields

| Field | Type | Notes |
|-------|------|-------|
| `idempotencyKey` | String | Primary key |
| `state` | String | Store as string (`PaymentState.name()`), never ordinal |
| `source` | String | |
| `destination` | String | |
| `receivedAt` | Instant | |
| `completedAt` | Instant | Nullable |
| `processedBy` | String | Node identifier |
| `retryCount` | int | Default 0 |
| `lastError` | String | Nullable |
| `version` | Long | **Required** — optimistic locking |

### OutboxEntry entity — required fields

| Field | Type | Notes |
|-------|------|-------|
| `id` | String | UUID primary key |
| `idempotencyKey` | String | FK / reference to ProcessedEvent |
| `status` | String | Store as string, never ordinal |
| `destination` | String | |
| `httpMethod` | String | Nullable |
| `endpoint` | String | Nullable, up to 1000 chars |
| `payload` | String | JSON-serialized body — large text field |
| `headers` | String | JSON-serialized `Map<String,String>` — large text field |
| `createdAt` | Instant | |
| `lastAttemptAt` | Instant | Nullable |
| `deliveredAt` | Instant | Nullable |
| `nextRetryAt` | Instant | Nullable |
| `retryCount` | int | Default 0 |
| `lastError` | String | Nullable |
| `lastHttpStatus` | Integer | Nullable |
| `lockedBy` | String | Nullable — UUID of the processor node that holds the lock |
| `lockExpiresAt` | Instant | Nullable |
| `version` | Long | **Required** — optimistic locking |

> **Never drop a field.** All fields are used by framework internals (`OutboxProcessor`, `DeliveryService`, `IdempotencyService`). A missing field will cause `NullPointerException` or silent data loss at runtime.

---

## Step 4: Implement ProcessedEventRepository

Create `{Name}ProcessedEventRepository implements ProcessedEventRepository` in the `repository/` package.

### Structural pattern

```java
@Component
public class {Name}ProcessedEventRepository implements ProcessedEventRepository {

    private final {Name}ProcessedEventStore store; // Spring Data repo or client

    public {Name}ProcessedEventRepository({Name}ProcessedEventStore store) {
        this.store = store;
    }

    @Override
    @Transactional
    public ProcessedEvent save(ProcessedEvent event) {
        var entity = toEntity(event);
        entity = store.save(entity);
        return toModel(entity);
    }

    // --- implement all remaining interface methods ---

    private ProcessedEvent toModel({Name}ProcessedEventEntity e) {
        return ProcessedEvent.builder()
            .idempotencyKey(e.getIdempotencyKey())
            .state(PaymentState.valueOf(e.getState()))  // string → enum
            .source(e.getSource())
            .destination(e.getDestination())
            .receivedAt(e.getReceivedAt())
            .completedAt(e.getCompletedAt())
            .processedBy(e.getProcessedBy())
            .retryCount(e.getRetryCount())
            .lastError(e.getLastError())
            .version(e.getVersion())
            .build();
    }

    private {Name}ProcessedEventEntity toEntity(ProcessedEvent m) {
        // Map all fields. Do not omit any.
        // store state as m.getState().name() — string, not ordinal
    }
}
```

### @Transactional requirements

| Method | Needs @Transactional |
|--------|---------------------|
| `save()` | YES |
| `updateState()` | YES |
| `markCompleted()` | YES |
| `deleteCompletedBefore()` | YES |
| `findByIdempotencyKey()` | No |
| `existsByIdempotencyKey()` | No |
| `findByState()` | No |
| `findStuckProcessing()` | No |
| `findRecentFailures()` | No |
| `countByState()` | No |

---

## Step 5: Implement OutboxRepository

Create `{Name}OutboxRepository implements OutboxRepository` in the `repository/` package.

### Critical: tryLock() must be atomic

`tryLock()` MUST be a single atomic conditional update. Never read the row first and then write:

```sql
-- Correct: single CAS update
UPDATE outbox_entries
SET locked_by = :lockedBy,
    lock_expires_at = NOW() + INTERVAL ':lockDurationSeconds' SECOND,
    version = version + 1
WHERE id = :entryId
  AND (locked_by IS NULL OR lock_expires_at < NOW())
```

Returns `true` if one row was updated (lock acquired), `false` if zero rows updated (already locked).

**Why atomicity matters:** A read-then-write is a race condition. Two processor nodes can both read "unlocked", both write their own `lockedBy`, and both believe they hold the lock — causing duplicate delivery to the downstream system, which is a financial error.

### Critical: releaseLock() must validate ownership

```sql
UPDATE outbox_entries
SET locked_by = NULL,
    lock_expires_at = NULL
WHERE id = :entryId
  AND locked_by = :lockedBy   -- CRITICAL: only release if you own it
```

**Why the ownership check matters:** Without `AND locked_by = :lockedBy`, a crashed node coming back online could release a lock that was already recovered and re-acquired by a healthy node, causing a second concurrent delivery.

### findReadyForProcessing() query

Must return entries where:
- `status = 'PENDING'`, OR
- `status = 'RETRY_PENDING'` AND `next_retry_at <= NOW()`

Ordered by `created_at ASC` (FIFO — oldest entries first).

### recordFailure() — exponential backoff

```java
// Formula: delay = min(baseDelay × 2^retryCount, maxDelay)
long delaySeconds = Math.min(
    config.getRetryBaseDelaySeconds() * (long) Math.pow(2, entry.getRetryCount()),
    config.getRetryMaxDelaySeconds()
);
Instant nextRetryAt = Instant.now().plusSeconds(delaySeconds);

// After maxRetries: mark as permanently FAILED, not RETRY_PENDING
if (entry.getRetryCount() >= config.getMaxRetries()) {
    markFailed(entry.getId(), error);
} else {
    // set status = RETRY_PENDING, nextRetryAt, retryCount++
}
```

---

## Step 6: Wire Up Auto-Configuration

If both repository beans are annotated `@Component` and are on the classpath, the starter auto-wires them automatically — no changes to `PaymentFrameworkAutoConfiguration` required.

Only create a separate `@AutoConfiguration` class if the adapter needs to configure its own connection factory, custom properties, or conditional bean creation. In that case, register it via:

```
payment-framework-{name}/src/main/resources/META-INF/spring/
  org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

With content:
```
com.dev.payments.framework.{name}.config.{Name}AdapterAutoConfiguration
```

---

## Step 7: Schema / Migrations (Persistence Adapters)

Reference the Oracle DDL for the authoritative column list and index requirements:
```
database/oracle/V1__create_payment_tables.sql
```

### Required indexes — do not omit any

**`processed_events`:**
- Index on `state` — used by `findByState()` and `findStuckProcessing()`
- Index on `received_at` — used by `findStuckProcessing()` and cleanup

**`outbox_entries`:**
- Index on `status` — used by `findReadyForProcessing()`
- Index on `next_retry_at` — used by retry polling
- Index on `idempotency_key` — used by FK lookups
- Index on `lock_expires_at` — used by `findExpiredLocks()`
- **Composite index on `(status, next_retry_at, created_at)`** — the polling query's primary access path; without this, polling degrades to a full table scan under load

---

## Troubleshooting

**Framework beans not activating (no idempotency checks running)**

The starter requires both `ProcessedEventRepository` and `OutboxRepository` beans via `@ConditionalOnBean`. Check:
- Both `@Component` classes are in a package scanned by Spring (under `com.dev.payments.framework.{name}`)
- The consumer application has the adapter on its classpath
- No `@Primary` conflict with another repository implementation

**Optimistic locking exceptions (`ObjectOptimisticLockingFailureException`) at high load**

This is expected and correct behavior — two threads tried to update the same row concurrently. The framework is designed to handle this. The caller (OutboxProcessor) retries the operation. If you're seeing this more than expected, check that `@Version` is correctly mapped and not being manually set.

**`tryLock()` never returns `true`**

The conditional update query may not be executing atomically. Check:
- The `WHERE` clause includes `(locked_by IS NULL OR lock_expires_at < NOW())`
- The time comparison uses the database's clock (`NOW()`), not the application's `Instant.now()`
- The update returns a row count (1 = acquired, 0 = not acquired)

**H2 dialect errors in tests**

H2 uses different SQL syntax from Oracle for some operations (e.g. `INTERVAL` clauses, `CLOB` types). Place H2-compatible DDL in `src/test/resources/schema.sql` — Spring Boot runs it automatically before tests. Use `@DataJpaTest` with `@AutoConfigureTestDatabase(replace = ANY)` to force H2.
