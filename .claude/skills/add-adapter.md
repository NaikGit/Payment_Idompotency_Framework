---
name: add-adapter
description: Scaffold a new persistence or messaging adapter module. Implements ProcessedEventRepository and OutboxRepository so the framework works with a new datastore (MongoDB, Redis, etc.) or messaging technology (Kafka, IBM MQ, etc.).
---

# Add New Adapter Module

An adapter bridges the framework's core repository interfaces to a specific datastore or messaging technology. Follow every step in order.

## Step 0 — Gather Requirements

Ask the user:
1. **Adapter name** (short, lowercase) — e.g. `redis`, `mongodb`, `kafka`, `ibmmq`
2. **Adapter type**:
   - `persistence` — implements both `ProcessedEventRepository` and `OutboxRepository` (stores state in a database/cache)
   - `messaging` — provides message listener infrastructure (Kafka consumer, IBM MQ listener, etc.)
3. **Technology and library** — e.g. "MongoDB with Spring Data MongoDB (`spring-boot-starter-data-mongodb`)"
4. **Any additional dependencies** beyond the Spring Boot starter

Full artifact ID will be: `payment-framework-{name}`

## Step 1 — Scaffold the Base Module

Run `/add-module` first with:
- Artifact ID: `payment-framework-{name}`
- Description: `{Technology} adapter for the payment idempotency framework`
- Dependencies: as specified in Step 0

Do not proceed until the module scaffold is complete and `mvn validate` returns `BUILD SUCCESS`.

## Step 2 — Create Package Layout

Under `payment-framework-{name}/src/main/java/com/bank/payments/framework/{name}/`, create:

```
{name}/
├── entity/       ← datastore-specific entity/document classes
├── repository/   ← adapter implementations + internal query interfaces
└── config/       ← (only if connection factory or custom auto-config needed)
```

## Step 3 — Create Entity Classes (Persistence Adapters)

Create two entity classes. Use the JPA entities as the canonical reference:
- `/Users/sejalnaik/payment-framework/payment-framework-jpa/src/main/java/com/bank/payments/framework/jpa/entity/ProcessedEventEntity.java`
- `/Users/sejalnaik/payment-framework/payment-framework-jpa/src/main/java/com/bank/payments/framework/jpa/entity/OutboxEntryEntity.java`

**Read those files before writing new entities** to ensure field parity.

### ProcessedEvent entity (`{Name}ProcessedEventDocument` or similar)

Must map every field from the `ProcessedEvent` domain model:

| Domain field | Type | Storage rule |
|---|---|---|
| `idempotencyKey` | String | Primary key |
| `state` | PaymentState | Store as **String** — never ordinal |
| `source` | String | |
| `destination` | String | |
| `receivedAt` | Instant | ISO-8601 string or native timestamp |
| `completedAt` | Instant | Nullable |
| `processedBy` | String | Node identifier |
| `retryCount` | int | Default 0 |
| `lastError` | String | Nullable, up to 2000 chars |
| `version` | Long | **REQUIRED** — optimistic locking |

**Critical:** `@Version` (or technology equivalent) MUST be present. Missing it means two concurrent updates can silently overwrite each other.

### OutboxEntry entity (`{Name}OutboxEntryDocument` or similar)

Must map every field from the `OutboxEntry` domain model:

| Domain field | Type | Storage rule |
|---|---|---|
| `id` | String | UUID, primary key |
| `idempotencyKey` | String | FK / reference to ProcessedEvent |
| `status` | OutboxStatus | Store as **String** |
| `destination` | String | |
| `httpMethod` | String | Nullable (REST only) |
| `endpoint` | String | Nullable (REST only), up to 1000 chars |
| `payload` | String | JSON serialized — use `@Lob` / large text field |
| `headers` | String | JSON serialized map — use `@Lob` / large text field |
| `createdAt` | Instant | |
| `lastAttemptAt` | Instant | Nullable |
| `deliveredAt` | Instant | Nullable |
| `nextRetryAt` | Instant | Nullable |
| `retryCount` | int | Default 0 |
| `lastError` | String | Nullable |
| `lastHttpStatus` | Integer | Nullable |
| `lockedBy` | String | Nullable — node UUID holding the lock |
| `lockExpiresAt` | Instant | Nullable |
| `version` | Long | **REQUIRED** — optimistic locking |

## Step 4 — Implement ProcessedEventRepository

Create `{Name}ProcessedEventRepository implements ProcessedEventRepository` in the `repository/` package.

### Pattern (read JPA reference first)
```
/Users/sejalnaik/payment-framework/payment-framework-jpa/src/main/java/com/bank/payments/framework/jpa/repository/JpaProcessedEventRepository.java
```

```java
@Component
public class {Name}ProcessedEventRepository implements ProcessedEventRepository {

    private final {Name}ProcessedEventStore store; // Spring Data repo or equivalent

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

    // ... implement all 10 interface methods

    private ProcessedEvent toModel({Name}ProcessedEventEntity e) {
        return ProcessedEvent.builder()
            .idempotencyKey(e.getIdempotencyKey())
            .state(PaymentState.valueOf(e.getState()))
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
        // map all fields — do not omit any
    }
}
```

### @Transactional requirement — ALL mutating methods:

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

## Step 5 — Implement OutboxRepository

Create `{Name}OutboxRepository implements OutboxRepository` in the `repository/` package.

### Critical Methods

**`tryLock()` — MUST be atomic (single conditional update)**

This must be a single atomic operation at the datastore level — never read-then-write:

```sql
-- SQL example (adapt to your technology):
UPDATE outbox_entries
SET locked_by = :lockedBy,
    lock_expires_at = NOW() + INTERVAL ':lockDurationSeconds' SECOND
WHERE id = :entryId
  AND (locked_by IS NULL OR lock_expires_at < NOW())
```

The method returns `true` if the row was updated (lock acquired), `false` otherwise.

**Why:** A read-then-write pattern is a race condition. Two nodes could both read "unlocked", then both write their lock ID, and both believe they hold the lock — causing duplicate delivery.

**`releaseLock()` — MUST validate the owner**

```sql
UPDATE outbox_entries
SET locked_by = NULL,
    lock_expires_at = NULL
WHERE id = :entryId
  AND locked_by = :lockedBy   -- ← CRITICAL: only release if YOU own the lock
```

**Why:** Without the `AND locked_by = :lockedBy` check, a crashed node could release a lock that was already recovered and re-acquired by a live node — causing duplicate delivery.

**`findReadyForProcessing()` — correct query**

Must return entries where:
- `status = 'PENDING'` OR
- `status = 'RETRY_PENDING'` AND `next_retry_at <= NOW()`

Ordered by `created_at ASC` (FIFO — oldest first).

**`recordFailure()` — exponential backoff**

```java
// delay = min(baseDelay × 2^retryCount, maxDelay)
long delaySeconds = Math.min(
    config.getRetryBaseDelaySeconds() * (long) Math.pow(2, entry.getRetryCount()),
    config.getRetryMaxDelaySeconds()
);
Instant nextRetryAt = Instant.now().plusSeconds(delaySeconds);
```

### @Transactional requirement for OutboxRepository:

`tryLock`, `releaseLock`, `markDelivered`, `markFailed`, `recordFailure`, `save`, `deleteDeliveredBefore` — all require `@Transactional`.

## Step 6 — Wire Up Beans

If the adapter's `@Component` classes are on the classpath and a Spring Data repository is configured, the framework starter auto-wires them via `@ConditionalOnBean({ProcessedEventRepository.class, OutboxRepository.class})`. No changes to `PaymentFrameworkAutoConfiguration` are needed.

Only add a separate `@AutoConfiguration` class if the adapter needs to configure its own connection factory or custom properties. In that case, create:

```
payment-framework-{name}/src/main/resources/META-INF/spring/
  org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

With content:
```
com.dev.payments.framework.{name}.config.{Name}AdapterAutoConfiguration
```

## Step 7 — Write Schema / Migration (Persistence Adapters)

Reference the Oracle DDL for the complete column list and required indexes:
```
/Users/sejalnaik/payment-framework/database/oracle/V1__create_payment_tables.sql
```

**Required indexes — do not omit:**

For `processed_events`:
- Index on `state`
- Index on `received_at`

For `outbox_entries`:
- Index on `status`
- Index on `next_retry_at`
- Index on `idempotency_key`
- Index on `lock_expires_at`
- Composite index on `(status, next_retry_at, created_at)` — this is the polling query's index

Place migration files under:
```
payment-framework-{name}/src/main/resources/db/migration/
```

## Step 8 — Compile Check

```bash
cd /Users/sejalnaik/payment-framework && mvn compile -pl payment-framework-{name} -am 2>&1 | tail -15
```

Resolve all compilation errors before proceeding to tests.

## Final Checklist

- [ ] `/add-module` completed — `mvn validate` was `BUILD SUCCESS`
- [ ] Both `ProcessedEventRepository` and `OutboxRepository` fully implemented (all methods)
- [ ] Every mutating repository method has `@Transactional`
- [ ] Entity classes have `@Version` (or equivalent) for optimistic locking
- [ ] `tryLock()` is a single atomic conditional update — no read-then-write
- [ ] `releaseLock()` includes `AND locked_by = :lockedBy` in the WHERE clause
- [ ] `toModel()` uses the Builder pattern and maps ALL fields
- [ ] `toEntity()` maps ALL fields — nothing omitted
- [ ] Enums (`state`, `status`) stored as Strings
- [ ] Schema migration includes all required indexes
- [ ] `mvn compile` returns `BUILD SUCCESS`
- [ ] `PaymentFrameworkAutoConfiguration` was NOT modified
