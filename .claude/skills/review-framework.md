---
name: review-framework
description: Code review checklist for payment-framework contributions. Enforces the 9 architectural invariants. Label each finding BLOCKER, WARNING, or SUGGESTION.
---

# Payment Framework Code Review

You are reviewing a contribution to the payment-framework. Work through each section methodically. For every finding output one of:
- **BLOCKER** — must be fixed before merge; violates a safety or correctness guarantee
- **WARNING** — should be fixed; may merge with explicit acknowledgment and a follow-up ticket
- **SUGGESTION** — optional improvement; no merge gate

Ask the user to share the diff, describe what changed, or point you to the relevant files. Read the files as needed before reviewing.

---

## Section 1: Atomicity Invariant

**Rule:** `processPayment()` execution and `OutboxEntry` creation must commit in the same database transaction. If either fails, both must roll back.

- [ ] Is `IdempotencyService.processMessage()` annotated `@Transactional`? If not → **BLOCKER**
- [ ] Is there any `@Async`, `CompletableFuture`, `new Thread()`, or `TransactionTemplate` call that would split the transaction boundary between `processedEventRepository.save()` and `outboxRepository.save()`? If yes → **BLOCKER**
- [ ] Does any new service method save a `ProcessedEvent` without also saving an `OutboxEntry` in the same transaction (or vice versa)? → **BLOCKER**
- [ ] Are there any event listeners (`@TransactionalEventListener`, `@EventListener`) that perform outbox writes after the main transaction commits? → **BLOCKER** (defeats atomicity)

---

## Section 2: State Machine Discipline

**Rule:** All `PaymentState` transitions must go through `PaymentState.transitionTo()`. Direct field assignments that bypass the state machine are forbidden.

Valid transitions:
```
RECEIVED → PROCESSING
PROCESSING → SENDING | FAILED
SENDING → COMPLETED | FAILED
FAILED → SENDING | COMPLETED
COMPLETED → (terminal — no transitions allowed)
```

- [ ] Does any code call `entity.setState(X)` or `event.setState(X)` without first calling `currentState.transitionTo(X)`? → **BLOCKER**
- [ ] Does any code attempt a transition from `COMPLETED` to any other state? → **BLOCKER**
- [ ] If a new `PaymentState` value was added: is `canTransitionTo()` updated? Is `isTerminal()` updated? Is `isRetryable()` updated? Missing any → **BLOCKER**
- [ ] Are all new transitions covered by tests (both valid path and invalid path throwing `InvalidStateTransitionException`)? Missing → **WARNING**

---

## Section 3: Locking Correctness

**Rule:** `tryLock()` must be atomic. `releaseLock()` must validate ownership before releasing.

- [ ] `tryLock()` implementation: is it a single atomic conditional UPDATE?
  - Correct: `UPDATE ... SET locked_by=? WHERE id=? AND (locked_by IS NULL OR lock_expires_at < NOW())`
  - Incorrect: read the row first, then update if unlocked → **BLOCKER** (race condition causes duplicate delivery)
- [ ] `releaseLock()` implementation: does the WHERE clause include `AND locked_by = :lockedBy`?
  - Missing this check → **BLOCKER** (a stale/crashed node could release a live node's lock)
- [ ] Is the `lockedBy` value a stable, per-node UUID (set once at startup), not a random value generated per `tryLock()` call?
  - Per-call random value → **BLOCKER** (lock can never be released by the same caller)
- [ ] Is the lock duration passed as seconds and correctly computed as `NOW() + lockDurationSeconds`? → **WARNING** if unit is wrong

---

## Section 4: Optimistic Locking

**Rule:** Every entity class must have a `@Version`-annotated field of type `Long`. This prevents lost updates under concurrent writes.

- [ ] Does every new entity class have a `Long version` field annotated `@Version` (or technology equivalent)? Missing → **BLOCKER**
- [ ] Was `@Version` removed from any existing entity? → **BLOCKER**
- [ ] Does any code manually set the `version` field (e.g. `entity.setVersion(1L)`)? Manual version management defeats the ORM's concurrency control → **WARNING**

---

## Section 5: @Transactional on Repository Methods

**Rule:** All repository methods that mutate state must be `@Transactional`.

Scan every class in the diff that implements `ProcessedEventRepository` or `OutboxRepository`:

| Method | Required |
|--------|---------|
| `save()` | YES |
| `updateState()` | YES |
| `markCompleted()` | YES |
| `deleteCompletedBefore()` | YES |
| `markDelivered()` | YES |
| `markFailed()` | YES |
| `recordFailure()` | YES |
| `tryLock()` | YES |
| `releaseLock()` | YES |

Any missing `@Transactional` annotation on the above → **BLOCKER**.

Read-only methods (`findBy*`, `existsBy*`, `countBy*`) do not require `@Transactional`.

---

## Section 6: Metrics Instrumentation

**Rule:** Key operations must emit Micrometer metrics. New operations without metrics are incomplete.

- [ ] Does any new significant operation (delivery path, state transition, retry logic, error path) fail to emit a counter or timer via `MeterRegistry`? → **WARNING**
- [ ] Are metric names following the convention `payment.{component}.{operation}`?
  - Examples: `payment.outbox.delivery.success`, `payment.idempotency.duplicates`
  - Non-conforming names → **SUGGESTION**
- [ ] Is `MeterRegistry` injected via constructor (not `@Autowired` field injection)?
  - Field injection → **WARNING** (framework pattern is constructor injection; makes testing harder)
- [ ] Are metric instances initialized in the constructor (not created lazily per-call)?
  - Lazy initialization creates new meter registrations on every call → **WARNING**

---

## Section 7: Retry and Failure Classification

**Rule:** Failures must be classified correctly. Wrong classification causes infinite retries (treating permanent errors as retryable) or lost deliveries (treating transient errors as permanent).

**Retryable HTTP status codes:** 408, 429, 500, 502, 503, 504
**Permanent failure codes:** 400, 401, 403, 404, 405, 409, 410, 422 (and any other 4xx not listed as retryable)

- [ ] Does any new retry logic treat a 4xx response (other than 408 and 429) as retryable? → **BLOCKER** (will retry forever against an endpoint that will never accept the request)
- [ ] Does any code retry a 400 Bad Request? → **BLOCKER**
- [ ] Is the exponential backoff formula `delay = min(baseDelay × 2^retryCount, maxDelay)` used? A custom formula that differs → **WARNING**
- [ ] Is `maxRetries` from `OutboxProcessorConfig` respected? Any code that retries beyond `config.getMaxRetries()` → **BLOCKER**
- [ ] After exhausting retries, is the entry marked `FAILED` (permanent) rather than `RETRY_PENDING`? → **BLOCKER** if not

---

## Section 8: Module and Package Structure

Only applies when the PR adds a new module.

- [ ] New module registered in parent `pom.xml` `<modules>` block? Missing → **BLOCKER** (module not built)
- [ ] New module registered in parent `pom.xml` `<dependencyManagement>` block? Missing → **WARNING** (version not managed for consumers)
- [ ] New module added as `<optional>true</optional>` in `payment-framework-starter/pom.xml`? Missing → **WARNING** (becomes mandatory transitive dep)
- [ ] Artifact ID follows pattern `payment-framework-{technology}`? Deviation → **SUGGESTION**
- [ ] Package follows `com.dev.payments.framework.{technology}`? Deviation → **SUGGESTION**
- [ ] No `<version>` tags on BOM-managed dependencies in the new module pom? Version override → **WARNING**

---

## Section 9: Test Coverage

- [ ] New repository adapter: does it have integration tests (H2 or TestContainers)?
  - Missing integration tests for a new adapter → **BLOCKER**
- [ ] Is the idempotency invariant tested? (Same key sent twice → second call is a no-op, no new records created)
  - Missing → **BLOCKER** for new adapters, **WARNING** for bug fixes
- [ ] Is the atomicity invariant tested? (Failure after `processedEventRepository.save()` but before `outboxRepository.save()` → both are rolled back)
  - Missing → **WARNING**
- [ ] Are state machine transitions tested for both valid paths and invalid paths (expect `InvalidStateTransitionException`)?
  - Missing invalid-path tests → **WARNING**
- [ ] Is `tryLock()` concurrency tested? (Two concurrent lock attempts → exactly one succeeds)
  - Missing for a new adapter → **BLOCKER**
- [ ] Do tests follow BDD Given-When-Then structure (`@DisplayName` + `@Nested`, or `given_when_then` method names)?
  - Non-BDD test structure → **SUGGESTION**
- [ ] Bug fix PRs: is there a regression test that fails before the fix and passes after?
  - Missing regression test → **WARNING**

---

## Summary

After completing all sections, output this table:

| # | Section | Status | Findings |
|---|---------|--------|----------|
| 1 | Atomicity | PASS / FAIL | |
| 2 | State Machine | PASS / FAIL | |
| 3 | Locking | PASS / FAIL | |
| 4 | Optimistic Locking | PASS / FAIL | |
| 5 | @Transactional | PASS / FAIL | |
| 6 | Metrics | PASS / FAIL | |
| 7 | Retry / Backoff | PASS / FAIL | |
| 8 | Module Structure | PASS / FAIL / N/A | |
| 9 | Test Coverage | PASS / FAIL | |

**Overall verdict:**
- **APPROVE** — no BLOCKERs, no unacknowledged WARNINGs
- **REQUEST CHANGES** — one or more BLOCKERs present
- **NEEDS DISCUSSION** — architectural questions that require team input before proceeding
