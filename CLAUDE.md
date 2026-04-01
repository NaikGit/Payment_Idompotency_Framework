# Payment Idempotency Framework — Claude Context

## What This Project Is

A multi-module Spring Boot 3.2 / Java 17 Maven framework that enforces **exactly-once payment processing** via the Outbox pattern. A bug in this codebase is a financial incident. Hold every change to a higher correctness bar than ordinary software.

---

## Available Skills — Use These First

Before writing code for any of these tasks, invoke the matching skill:

| Task | Skill to run |
|------|-------------|
| Add a new adapter (MongoDB, Redis, Kafka, etc.) | `/add-adapter` |
| Add any new Maven module | `/add-module` |
| Review a PR or code change | `/review-framework` |
| Write tests for any component | `/test-payment` |
| Write or review any Java code | `/java-coding-standards` |

Do not improvise these tasks — the skills encode project-specific invariants and patterns that must be followed exactly.

---

## Module Structure

```
payment-framework/
├── payment-framework-core/      # Interfaces, models, state machine — no JPA, no Spring Data
├── payment-framework-jpa/       # JPA/Oracle adapter (the ONLY fully implemented adapter)
├── payment-framework-starter/   # Spring Boot auto-configuration
├── examples/                    # Reference implementation
├── database/oracle/             # DDL — authoritative column/index reference
└── docs/                        # Developer guides (HOW_TO_ADD_ADAPTER, TESTING_GUIDE, etc.)
```

Parent POM: `com.bank.payments:payment-idempotency-framework:1.0.0-SNAPSHOT`
Package root: `com.dev.payments.framework`

**MongoDB, Kafka, IBM MQ modules are declared in parent POM but have no source yet** — they are future work.

---

## The Eight Non-Negotiable Invariants

Violating any of these is a BLOCKER. Never propose code that breaks them.

1. **Atomicity** — `processPayment()` and `OutboxEntry` creation commit in the same `@Transactional` boundary. No `@Async`, no thread handoff between the two saves.

2. **State machine** — All `PaymentState` transitions go through `PaymentState.transitionTo()`. Never assign `.state` directly.
   Valid transitions: `RECEIVED→PROCESSING`, `PROCESSING→SENDING|FAILED`, `SENDING→COMPLETED|FAILED`, `FAILED→SENDING|COMPLETED`. `COMPLETED` is terminal.

3. **Locking atomicity** — `tryLock()` must be a single atomic conditional UPDATE (CAS). Never read-then-write. `releaseLock()` must include `AND locked_by = :lockedBy` — prevents cross-node lock release.

4. **Optimistic locking** — Every entity class has a `@Version Long version` field. Never remove it, never set it manually.

5. **@Transactional on mutations** — `save`, `updateState`, `markCompleted`, `markDelivered`, `markFailed`, `recordFailure`, `tryLock`, `releaseLock`, `deleteCompletedBefore`, `deleteDeliveredBefore` all require `@Transactional`.

6. **Metrics** — Every significant operation emits a Micrometer counter or timer. Names follow `payment.{component}.{operation}`. `MeterRegistry` injected via constructor, meters initialized in constructor.

7. **Failure classification** — Retryable: `408, 429, 5xx`. Permanent: all other `4xx`. Treating `400` as retryable causes infinite retry loops.

8. **Backoff formula** — `delay = min(baseDelay × 2^retryCount, maxDelay)`. After `maxRetries`, status → permanently `FAILED`.

---

## Core Interfaces — Know Before Touching Repositories

Two interfaces in `payment-framework-core` that every persistence adapter must implement fully:

- `ProcessedEventRepository` — 10 methods, tracks idempotency lifecycle
- `OutboxRepository` — 14 methods, manages delivery queue

The JPA implementations are the canonical reference:
- `payment-framework-jpa/.../jpa/repository/JpaProcessedEventRepository.java`
- `payment-framework-jpa/.../jpa/entity/ProcessedEventEntity.java`

Always read these before writing a new adapter.

---

## Coding Standards (summary — full detail in `/java-coding-standards`)

- **Clean Code:** classes = nouns, methods = verbs, booleans = predicates. No noise words. Max ~20 lines per method. No boolean flag params. Return `Optional<T>`, never `null`.
- **SOLID:** one reason to change per class. Depend on interfaces, not concretions. Constructor injection only — no `@Autowired` fields.
- **Design patterns in use:** Builder (all domain models), Adapter (repository impls), State Machine (`PaymentState`), Repository (interfaces), Strategy (`DeliveryType`), Template Method (`PaymentMessageHandler` defaults). Extend these — do not reinvent them.
- **Do not introduce:** Singleton (use `@Component`), Service Locator (`getBean`), God classes.

---

## Testing Approach (summary — full detail in `/test-payment`)

**No Docker. No TestContainers.** Use embedded infrastructure per adapter:

| Adapter | Test tool |
|---------|----------|
| JPA / Oracle | H2 via `@DataJpaTest` + `@AutoConfigureTestDatabase` |
| Kafka | `@EmbeddedKafka` via `spring-kafka-test` |
| MongoDB | Flapdoodle via `de.flapdoodle.embed.mongo.spring30x` + `@DataMongoTest` |
| IBM MQ | Mock `JmsTemplate` with Mockito |
| Redis | Mock `RedisTemplate` with Mockito |

All tests use BDD Given-When-Then structure with JUnit 5 + AssertJ. Two styles:
- `@DisplayName` + `@Nested` for complex scenarios
- `given{Context}_when{Action}_then{Outcome}` method names for simple tests

---

## What NOT to Do

- Do not modify `PaymentFrameworkAutoConfiguration` when adding a new adapter — `@ConditionalOnBean` picks up new repository beans automatically.
- Do not add `<version>` tags to dependencies managed by the Spring Boot BOM in any module pom.
- Do not commit directly to the state of any domain model field — go through the state machine.
- Do not return `null` from public methods — use `Optional<T>`.
- Do not add tests that require Docker.
- Do not create helpers, utilities, or abstractions that are only used once.
