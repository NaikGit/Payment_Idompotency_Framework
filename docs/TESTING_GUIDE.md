# Testing Guide

## Philosophy

Tests in this project are correctness guarantees, not just coverage metrics. A bug here is a financial incident — duplicate payments, missed payments, or lost delivery state. Every test should have a clear financial correctness rationale: "this test ensures that [specific invariant] holds under [specific condition]."

Write tests you would want to exist if you were on-call at 2am dealing with a production incident.

---

## Test Stack

No Docker or TestContainers. All infrastructure is either embedded or mocked.

| Tool | Scope | Purpose |
|------|-------|---------|
| JUnit 5 | 5.10.0 | Test runner, `@ParameterizedTest`, `@Nested` |
| Mockito | via `spring-boot-starter-test` | Mocking infrastructure dependencies |
| AssertJ | via `spring-boot-starter-test` | Fluent, readable assertions |
| H2 | in-memory | JPA adapter tests (replaces Oracle) |
| `spring-kafka-test` (`@EmbeddedKafka`) | test scope | Embedded Kafka broker for Kafka adapter tests |
| Flapdoodle (`de.flapdoodle.embed.mongo`) | test scope | Embedded MongoDB for MongoDB adapter tests |
| Mockito (JMS/Redis) | — | IBM MQ and Redis have no lightweight embedded option — mock the client |

H2, `spring-kafka-test`, and Flapdoodle are all in the Spring Boot BOM — no `<version>` tag needed in pom.xml.

---

## BDD Structure: Given-When-Then

Every test must follow Given-When-Then structure. See `/java-coding-standards` Part 3 for the full rules. Short version:

- **Given** — set up the pre-conditions (arrange)
- **When** — execute exactly one action (act)
- **Then** — assert the outcome (assert)

**Preferred style for complex scenarios (`@DisplayName` + `@Nested`):**

```java
@DisplayName("IdempotencyService")
class IdempotencyServiceTest {

    @Nested
    @DisplayName("given a key that has already been processed to COMPLETED")
    class GivenCompletedKey {

        @Test
        @DisplayName("when processMessage is called again, then returns duplicate result and creates no new records")
        void whenProcessMessage_thenReturnsDuplicate() {
            // Given
            when(repo.findByIdempotencyKey("txn-001"))
                .thenReturn(Optional.of(completedEvent("txn-001")));
            // When / Then ...
        }
    }
}
```

**Acceptable style for simple unit tests (method name encodes context):**

```java
@Test
void givenExpiredLock_whenFindExpiredLocks_thenEntryIsReturned() { ... }
```

---

## Setting Up Test Infrastructure

### JPA / Oracle adapter: H2 in-memory

Use `@DataJpaTest` which auto-configures H2 as the in-memory database:

```java
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
class JpaOutboxRepositoryTest { ... }
```

For `@SpringBootTest` end-to-end tests also use H2:
```java
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
class JpaIdempotencyIntegrationTest { ... }
```

If production schema uses Oracle-specific syntax (e.g. `INTERVAL`, `CLOB`), place H2-compatible DDL in:
```
src/test/resources/schema.sql
```
Spring Boot runs it automatically before tests when `spring.jpa.hibernate.ddl-auto=none`.

### Kafka adapter: `@EmbeddedKafka`

Add to test scope in the module pom (version managed by Spring Boot BOM):
```xml
<dependency>
    <groupId>org.springframework.kafka</groupId>
    <artifactId>spring-kafka-test</artifactId>
    <scope>test</scope>
</dependency>
```

```java
@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = {"payment-events"})
class KafkaPaymentListenerIntegrationTest {
    // Spring injects the embedded broker URL automatically via
    // spring.kafka.bootstrap-servers = ${spring.embedded.kafka.brokers}
    // (set this in src/test/resources/application-test.yml)
}
```

### MongoDB adapter: Flapdoodle embedded MongoDB

Add to test scope in the module pom (version managed by Spring Boot BOM):
```xml
<dependency>
    <groupId>de.flapdoodle.embed</groupId>
    <artifactId>de.flapdoodle.embed.mongo.spring30x</artifactId>
    <scope>test</scope>
</dependency>
```

Use `@DataMongoTest` — starts the embedded MongoDB, no full Spring context needed:
```java
@DataMongoTest
class MongoProcessedEventRepositoryTest { ... }
```

### IBM MQ and Redis adapters: mock the client

Neither IBM MQ nor Redis has a lightweight embedded option suitable for unit/integration tests. Mock the client directly with Mockito:

```java
// IBM MQ
@Mock JmsTemplate jmsTemplate;
@Mock MQConnectionFactory connectionFactory;

// Redis
@Mock RedisTemplate<String, String> redisTemplate;
@Mock ValueOperations<String, String> valueOps;
```

For IBM MQ, focus tests on the listener logic and idempotency service interaction — not on the JMS transport itself.

### Mocking MeterRegistry

`MeterRegistry` builder chains must be fully stubbed — the framework initializes meters in constructors:

```java
@BeforeEach
void setUpMetrics() {
    Counter counter = mock(Counter.class);
    Timer timer = mock(Timer.class);
    when(meterRegistry.counter(anyString(), any(String[].class))).thenReturn(counter);
    when(meterRegistry.timer(anyString(), any(String[].class))).thenReturn(timer);
    // Use SimpleMeterRegistry for integration tests where real metrics are needed:
    // MeterRegistry meterRegistry = new SimpleMeterRegistry();
}
```

---

## Test Category 1: Handler Unit Tests

**What to test:** Business logic inside `processPayment()`, `validate()`, and optional callbacks.
**What NOT to test here:** Framework wiring, Spring context, idempotency checks.
**Setup:** Instantiate the handler directly; mock only its direct dependencies.

Required test cases for every `PaymentMessageHandler` implementation:

| Scenario | Test name pattern |
|----------|------------------|
| Valid input produces correct `OutboxPayload` endpoint and body | `givenValidInput_whenProcessPayment_thenReturnsCorrectPayload` |
| Dependency throws → exception propagates (no swallowing) | `givenDependencyThrows_whenProcessPayment_thenExceptionPropagates` |
| Null/invalid input → `validate()` returns false | `givenNullInput_whenValidate_thenReturnsFalse` |
| Valid input → `validate()` returns true | `givenValidInput_whenValidate_thenReturnsTrue` |
| `onDuplicateDetected()` is called when duplicate occurs (if overridden) | `givenDuplicate_whenOnDuplicateDetected_thenLogsOrCounters` |

---

## Test Category 2: Idempotency Tests

**Invariant:** Processing the same `idempotencyKey` twice must produce identical observable state. The second call must be a no-op — no new `ProcessedEvent`, no new `OutboxEntry`.

| Scenario | Test name pattern | Blocker if missing |
|----------|------------------|--------------------|
| Same key twice → second is duplicate | `givenKeyProcessedOnce_whenSubmittedAgain_thenSecondIsDuplicate` | YES |
| Two concurrent threads with same key → only one succeeds | `givenConcurrentSameKey_whenBothSubmit_thenOnlyOneProcessed` | YES for new adapters |
| Different keys → both processed independently | `givenTwoDifferentKeys_whenBothSubmitted_thenBothProcessed` | No |

---

## Test Category 3: Repository Adapter Tests

Write these for every new adapter implementing `ProcessedEventRepository` and `OutboxRepository`. Choose the right embedded tool per adapter type (H2 for JPA, Flapdoodle for MongoDB, mocks for IBM MQ and Redis).

### ProcessedEventRepository — required test coverage

| Method | Test scenario |
|--------|-------------|
| `save()` + `findByIdempotencyKey()` | Round-trip: save and find, assert all fields preserved |
| `existsByIdempotencyKey()` | Returns false for unknown key; returns true after save |
| `updateState()` | Changes state and sets `lastError`; returns false for unknown key |
| `markCompleted()` | Sets state to COMPLETED; sets `completedAt`; returns false for unknown key |
| `findByState()` | Returns only events matching the given state |
| `findStuckProcessing()` | Returns PROCESSING events older than threshold; ignores recent ones |
| `findRecentFailures()` | Returns FAILED events ordered by most recent; respects limit |
| `countByState()` | Returns correct count per state |
| `deleteCompletedBefore()` | Removes only COMPLETED events older than threshold; leaves others |

### OutboxRepository — required test coverage

| Method | Test scenario |
|--------|-------------|
| `save()` + `findById()` | Round-trip: save and find, assert all fields |
| `findReadyForProcessing()` | Returns PENDING; returns RETRY_PENDING where `nextRetryAt` is past; ignores RETRY_PENDING with future `nextRetryAt` |
| `tryLock()` | Returns true for unlocked entry; returns false for already-locked entry; concurrency test (10 threads, 1 winner) |
| `releaseLock()` | Returns true for correct owner; returns false for wrong owner; entry is unlocked after release |
| `findExpiredLocks()` | Returns entries where `lockExpiresAt < NOW()`; ignores active locks |
| `markDelivered()` | Sets status to DELIVERED and `deliveredAt`; returns false for unknown id |
| `recordFailure()` | Increments `retryCount`; sets `lastError`; calculates correct `nextRetryAt` using backoff formula; sets status to FAILED after `maxRetries` |
| `markFailed()` | Sets status to FAILED permanently |
| `countByStatus()` | Correct count per status |
| `deleteDeliveredBefore()` | Removes only DELIVERED entries older than threshold |

---

## Test Category 4: Locking and Concurrency Tests

These tests verify the atomic locking guarantees that prevent duplicate delivery.

```java
// Pattern for concurrency test
@Test
void givenPendingEntry_whenConcurrentLockAttempts_thenExactlyOneSucceeds()
    throws InterruptedException {

    String entryId = createPendingEntry();
    int threadCount = 10;
    AtomicInteger successCount = new AtomicInteger(0);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(threadCount);
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);

    for (int i = 0; i < threadCount; i++) {
        String nodeId = "node-" + i;
        executor.submit(() -> {
            try {
                startLatch.await();
                if (outboxRepository.tryLock(entryId, nodeId, 60)) {
                    successCount.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        });
    }

    startLatch.countDown();
    doneLatch.await(5, TimeUnit.SECONDS);
    executor.shutdownNow();

    assertThat(successCount.get())
        .as("exactly one thread must win the lock")
        .isEqualTo(1);
}
```

Additional locking tests:
- Wrong owner cannot release lock
- Expired lock appears in `findExpiredLocks()`
- After lock expiry, a new node can acquire the lock

---

## Test Category 5: State Machine Tests

```java
@ParameterizedTest(name = "{0} → {1}")
@MethodSource("validTransitions")
void givenValidTransition_whenTransitionTo_thenSucceeds(PaymentState from, PaymentState to) {
    assertThat(from.transitionTo(to)).isEqualTo(to);
}

@ParameterizedTest(name = "COMPLETED → {0} must throw")
@EnumSource(PaymentState.class)
void givenCompletedState_whenAnyTransition_thenThrows(PaymentState target) {
    assertThatThrownBy(() -> PaymentState.COMPLETED.transitionTo(target))
        .isInstanceOf(InvalidStateTransitionException.class);
}
```

Cover all valid paths and at least 4 invalid paths. If you add a new state, add test coverage for all its transitions before merging.

---

## Test Category 6: Retry and Backoff Logic

| Scenario | Formula to verify |
|----------|------------------|
| retryCount=0 | `delay = baseDelay × 2^0 = baseDelay` |
| retryCount=1 | `delay = baseDelay × 2^1 = 2 × baseDelay` |
| retryCount=2 | `delay = baseDelay × 2^2 = 4 × baseDelay` |
| retryCount at cap | `delay = maxDelay` (not baseDelay × 2^N) |
| retryCount = maxRetries | Status becomes FAILED, not RETRY_PENDING |

Default values from `OutboxProcessorConfig`: `baseDelay=30s`, `maxDelay=3600s`, `maxRetries=5`.

---

## Test File Placement

Mirror the source path under `src/test/java/`:

```
src/main/java/com/bank/payments/framework/jpa/repository/JpaProcessedEventRepository.java
                                           ↕
src/test/java/com/bank/payments/framework/jpa/repository/JpaProcessedEventRepositoryTest.java
```

Test resources:
```
src/test/resources/
├── schema.sql          ← H2-compatible DDL (if needed)
└── application-test.yml ← test-scope overrides (e.g. ddl-auto: create-drop)
```

---

## Running Tests

No Docker required — all infrastructure is embedded or mocked.

```bash
# All tests in the project
mvn test

# Single module
mvn test -pl payment-framework-jpa

# Specific class
mvn test -pl payment-framework-jpa -Dtest=JpaProcessedEventRepositoryTest

# Specific method
mvn test -pl payment-framework-jpa -Dtest=JpaProcessedEventRepositoryTest#givenSavedEvent_whenFindByIdempotencyKey_thenAllFieldsPreserved
```
