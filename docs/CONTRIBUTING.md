# Contributing to Payment Framework

## Introduction

This framework prevents duplicate payment processing. A correctness bug here is a financial incident — not a regression to fix in the next sprint. Every contribution must meet a higher bar than ordinary software because the consequences of getting it wrong are real money, real customers, and real regulatory exposure.

Read this document fully before raising a PR.

---

## Architecture Background

### The Outbox Pattern

When a payment message arrives, two things must happen atomically:
1. Record the `ProcessedEvent` (idempotency log — "we saw this message")
2. Create the `OutboxEntry` (delivery queue — "deliver this to the downstream system")

If only one of these commits, the system is in an inconsistent state. The outbox processor polls the `OutboxEntry` table independently and handles delivery with retries — decoupling receipt from delivery, and making the system crash-safe.

### Idempotency

The `processed_events` table is the source of truth for "have we seen this message before?" Every message carries an `idempotencyKey` (often the transaction ID). Before processing, the framework checks this key. If it exists, the message is a duplicate and is silently dropped. If it does not exist, the event is processed and both records are committed atomically.

### State Machine

`PaymentState` enforces a strict lifecycle:

```
RECEIVED → PROCESSING → SENDING → COMPLETED (terminal)
                      ↘ FAILED ↔ SENDING
```

All transitions go through `PaymentState.transitionTo()`. Direct state assignments are forbidden.

---

## Development Setup

**Requirements:**
- Java 17 (exact — not 21, not 11)
- Maven 3.8+
- No Docker required — tests use H2, embedded Kafka, Flapdoodle MongoDB, or mocks

**Build the full project:**
```bash
cd payment-framework
mvn clean verify
```

**Run tests for a single module:**
```bash
mvn test -pl payment-framework-jpa
```

---

## The Eight Architectural Invariants

These are non-negotiable. Violating any of them constitutes a BLOCKER in code review (see [Code Review](#code-review) below).

### 1. Atomicity

`processPayment()` execution and `OutboxEntry` creation must be in the same database transaction. Both commit, or both roll back.

```java
// Both saves happen inside a single @Transactional boundary
processedEventRepository.save(event);    // ← same transaction
outboxRepository.save(outboxEntry);      // ← same transaction
```

No `@Async`, no `CompletableFuture`, no `@TransactionalEventListener` between them.

### 2. State Machine Transitions

All `PaymentState` changes go through `PaymentState.transitionTo()`. Never assign `.state` directly.

```java
// Wrong
event.setState(PaymentState.PROCESSING);

// Right
PaymentState newState = event.getState().transitionTo(PaymentState.PROCESSING);
event.setState(newState);
```

### 3. Locking Atomicity

`tryLock()` must be a single atomic conditional UPDATE (CAS). Never read-then-write.
`releaseLock()` must validate `lockedBy` before releasing — prevents cross-node lock theft.

### 4. Optimistic Locking

Every entity class must have a `@Version Long version` field. This prevents lost updates when two threads update the same row concurrently.

### 5. @Transactional on Repository Mutations

All mutating repository methods — `save`, `updateState`, `markCompleted`, `markDelivered`, `markFailed`, `recordFailure`, `tryLock`, `releaseLock`, `deleteCompletedBefore`, `deleteDeliveredBefore` — must be annotated `@Transactional`.

### 6. Metrics on Key Operations

Every significant operation emits a Micrometer counter or timer. Metric names follow `payment.{component}.{operation}`. `MeterRegistry` is injected via constructor, and meters are initialized in the constructor (never lazily).

### 7. Failure Classification

HTTP failure codes must be classified correctly:

| Classification | HTTP codes |
|---------------|-----------|
| **Retryable** | 408, 429, 500, 502, 503, 504 |
| **Permanent** | All other 4xx (400, 401, 403, 404, 405, 409, 410, 422, …) |

Treating a `400 Bad Request` as retryable will cause infinite retries against an endpoint that will never accept the payload.

### 8. Exponential Backoff Formula

```java
delay = min(baseDelay × 2^retryCount, maxDelay)
```

Default values: `baseDelay=30s`, `maxDelay=3600s` (1 hour), `maxRetries=5`.
After `maxRetries`, status becomes permanently `FAILED`.

---

## Coding Standards

All code in this project follows Clean Code, SOLID principles, BDD test structure, and the framework's established design patterns. Run `/java-coding-standards` in Claude Code for the full reference. Key rules:

- **Naming:** classes = nouns, methods = verbs, booleans = predicates. No noise words (`Manager`, `Helper`, `Data`).
- **Functions:** one job, ≤20 lines, ≤3 parameters, no boolean flag arguments.
- **No null returns:** use `Optional<T>` from public methods.
- **Constructor injection:** all dependencies injected via constructor, not `@Autowired` fields.
- **SOLID:** each class has one reason to change; depend on interfaces, not concretions.

---

## Contribution Types

### Adding a new persistence or messaging adapter

Follow [HOW_TO_ADD_ADAPTER.md](HOW_TO_ADD_ADAPTER.md). Use `/add-adapter` in Claude Code for guided scaffolding.

Minimum requirements before PR:
- All 10 `ProcessedEventRepository` methods implemented
- All 14 `OutboxRepository` methods implemented
- All mutating methods `@Transactional`
- Entity classes have `@Version`
- `tryLock()` is atomic; `releaseLock()` validates ownership
- Schema migration with all required indexes
- Integration tests covering round-trip, idempotency, and locking

### Adding a new Maven module

Follow [HOW_TO_ADD_MODULE.md](HOW_TO_ADD_MODULE.md). Use `/add-module` in Claude Code for guided scaffolding.

### Fixing a bug

- **Add a regression test first.** The test must fail before the fix and pass after. This is the most important artifact of a bug fix.
- Do not fix adjacent issues in the same PR — keep the blast radius small.
- Update `CONTRIBUTING.md` if the bug revealed a gap in the invariant documentation.

### Changing the state machine

State machine changes affect every adapter and every consumer. Before raising a PR:
1. Open a discussion in the team channel with the proposed transition diagram
2. Get explicit approval from at least two engineers
3. In the PR: update `PaymentState.java`, update this document's invariant table, add tests for every new valid and invalid transition

### Adding configuration properties

1. Add the field to `OutboxProcessorConfig.java` with a sensible default
2. Document it in the `application.yml` example in `README.md`
3. Add a comment explaining the operational impact of the setting

---

## Code Review

All PRs are reviewed using the `/review-framework` Claude Code skill, which runs a 9-section checklist covering all eight architectural invariants plus module structure and test coverage.

Additionally, run `/java-coding-standards` to verify Clean Code, SOLID, and BDD compliance.

**Merge criteria:**
- Zero BLOCKERs
- All WARNINGs either fixed or acknowledged with a follow-up ticket
- All existing tests pass (`mvn test`)
- New code has tests at all applicable layers (see [TESTING_GUIDE.md](TESTING_GUIDE.md))

For optional additional review, use `/coderabbit:review` for AI-assisted static analysis.

---

## Branch and Commit Standards

**Branch naming:**
```
feature/{ticket-id}-{short-description}   e.g. feature/PAY-123-add-redis-adapter
fix/{ticket-id}-{short-description}       e.g. fix/PAY-456-trylock-race-condition
```

**Commit messages:**
- Imperative mood, present tense: `Add Redis adapter`, not `Added Redis adapter`
- First line ≤72 characters
- Body (optional): explain *why*, not *what*. The diff shows what.

```
Add Redis adapter for ProcessedEventRepository and OutboxRepository

Oracle JDBC is not available in the cloud-native deployment environment.
Redis provides sub-millisecond idempotency checks and distributed locking
via atomic SET NX EX commands, satisfying tryLock() atomicity requirements.
```

---

## Testing Requirements

Full details in [TESTING_GUIDE.md](TESTING_GUIDE.md). Minimum bar for any PR to merge:

| Contribution type | Minimum test requirement |
|------------------|--------------------------|
| New adapter | Integration tests for all repository methods + concurrency test for `tryLock()` |
| Bug fix | Regression test that fails before the fix |
| New feature | Unit + integration tests covering the happy path and at least one error path |
| State machine change | Tests for all new valid transitions + all new invalid transitions |
| Refactoring | Existing tests continue to pass; no new tests required if behavior is unchanged |

All tests use BDD Given-When-Then structure. See [TESTING_GUIDE.md](TESTING_GUIDE.md) for patterns and examples.

---

## Release Process

1. Update the version in the parent `pom.xml`: `1.0.0-SNAPSHOT` → `1.0.0` (or next version)
2. Run the full build: `mvn clean verify`
3. Tag the release in git: `git tag v1.0.0`
4. Deploy to the internal Maven repository: `mvn clean deploy`
5. Bump parent `pom.xml` to the next snapshot: `1.0.1-SNAPSHOT`
6. Update `README.md` with the new version in the dependency snippet
