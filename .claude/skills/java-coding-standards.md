---
name: java-coding-standards
description: Clean Code, SOLID principles, BDD Given-When-Then test structure, and design pattern guidance specific to the payment-framework. Invoke before writing new code or reviewing existing code in this project.
---

# Java Coding Standards — Payment Framework

Apply these standards to all code written or reviewed in this project. Four sections: Clean Code, SOLID, BDD Tests, and Design Patterns.

---

## Part 1 — Clean Code

These rules apply to every Java file in the project.

### Naming

| Element | Convention | Good | Bad |
|---------|-----------|------|-----|
| Class | Noun | `IdempotencyService`, `OutboxEntry` | `ProcessData`, `Manager` |
| Method | Verb | `processPayment()`, `markCompleted()` | `payment()`, `doStuff()` |
| Boolean | Predicate | `isTerminal()`, `canRetry()`, `isDuplicate()` | `terminal()`, `retryFlag` |
| Variable | Descriptive noun | `idempotencyKey`, `retryCount` | `key2`, `n`, `tmp` |
| Constant | UPPER_SNAKE | `MAX_RETRY_DELAY_SECONDS` | `maxDelay`, `MAX` |

**Rules:**
- No abbreviations except universally understood ones (`id`, `url`, `dto`)
- No noise words: never name a class `PaymentDataManager`, `PaymentInfoHelper`, or `PaymentServiceImpl` — be specific (`PaymentEventHandler`, `IdempotencyService`)
- Boolean variables and methods must read like a question: `if (event.isTerminal())` not `if (event.terminal())`

### Functions

- **Do one thing.** If you need "and" to describe what a method does, split it into two.
- **Size target:** aim for ≤20 lines. If a method is scrolling off screen, it is doing too much.
- **No boolean flag parameters.** `processMessage(key, source, dest, payload, true)` — what does `true` mean? Split into `processMessage(...)` and `processMessageSkippingValidation(...)`.
- **Max 3 parameters.** If a method needs more, introduce a parameter object (e.g. `ProcessMessageRequest`).
- **No side effects on query methods.** A method named `findByIdempotencyKey()` must not also update a timestamp.

### Classes

- **Single Responsibility.** One reason to change. Test: can you describe the class's job in one sentence without using "and"?
  - `IdempotencyService` — checks for duplicates and creates atomic event+outbox records. (One job: idempotency enforcement)
  - `DeliveryService` — sends outbox entries to their HTTP destinations. (One job: delivery)
- **Size target:** aim for ≤200 lines. A class approaching 500 lines is a warning sign of multiple responsibilities.

### Constants and Magic Values

- Never use string literals for state names: use `PaymentState.COMPLETED`, never `"COMPLETED"`.
- Never use raw numbers for durations or limits: use `config.getMaxRetries()`, never `5`.
- Never use `null` as a return value from public methods — return `Optional<T>` instead.
  ```java
  // Wrong
  public ProcessedEvent findByKey(String key) {
      return repository.findById(key).orElse(null); // forces null checks on callers
  }
  // Right
  public Optional<ProcessedEvent> findByKey(String key) {
      return repository.findById(key);
  }
  ```

### Comments

- Comments explain **why**, not **what**. The code explains what.
  ```java
  // Wrong: restates the code
  // Increment retry count
  retryCount++;

  // Right: explains why
  // Increment before calculating next delay so retry 0 uses base delay, not zero
  retryCount++;
  ```
- Delete commented-out code. Git history exists for a reason.
- If you feel the need to write a long comment to explain a method, consider whether the method should be renamed or broken up instead.

### Error Handling

- Throw **specific** exceptions — never `RuntimeException` or bare `Exception`:
  ```java
  // Wrong
  throw new RuntimeException("bad state");
  throw new RuntimeException("Failed to serialize payload", e);

  // Right
  throw new InvalidStateTransitionException("Cannot transition from " + current + " to " + target);
  throw new IllegalStateException("Failed to serialize outbox payload for key: " + key, e);
  ```

- Catch the **most specific type** available for the failure mode:

  | Failure | Catch type |
  |---------|-----------|
  | JSON serialization / deserialization | `JsonProcessingException` |
  | JMS header/property access | `JMSException` |
  | HTTP 4xx / 5xx responses | `WebClientResponseException` |
  | Illegal program state | `throw new IllegalStateException(...)` |

- **`catch (Exception e)` is prohibited in application logic.** It is only acceptable in three specific infrastructure scenarios, each requiring an explanatory comment:
  1. **Scheduled task / polling loop** — broad catch prevents the scheduled thread from dying permanently on an unexpected error
  2. **Executor-submitted tasks** — an uncaught exception in a submitted task would swallow the failure without recording it
  3. **Framework extension hooks** (`processPayment()`, `onPermanentFailure()`) — team-implemented code may throw any unchecked exception; always ack/return rather than propagate

  ```java
  // Wrong — no explanation, hides bugs
  } catch (Exception e) {
      log.error("Something went wrong", e);
  }

  // Right — explains why broad catch is required here
  } catch (Exception e) {
      // Broad catch is intentional: poll() runs on a scheduler thread.
      // Letting any exception propagate would kill the scheduled task permanently.
      log.error("Error in outbox processor poll", e);
  }
  ```

- Never silently swallow exceptions — always log at an appropriate level and either rethrow, record a failure, or acknowledge (to avoid infinite redelivery loops).
- Exceptions for exceptional cases only. Don't use exceptions for flow control.

---

## Part 2 — SOLID Principles

### S — Single Responsibility

**Rule:** A class has one reason to change.

| Class | Single responsibility | Would violate SRP if... |
|-------|----------------------|------------------------|
| `IdempotencyService` | Idempotency checking + atomic event creation | ...it also sent HTTP requests |
| `DeliveryService` | HTTP delivery via WebClient | ...it also updated ProcessedEvent state |
| `OutboxProcessor` | Polling and coordinating delivery | ...it also implemented the HTTP client logic |
| `JpaProcessedEventRepository` | JPA ↔ domain model translation | ...it also contained business rules about when to retry |

**Check:** If adding a new requirement (e.g. "add audit logging") forces you to change a class that already works, that class has more than one responsibility.

### O — Open/Closed

**Rule:** Open for extension, closed for modification.

The framework is specifically designed around this principle:
- Add a new adapter by creating new classes — do NOT modify `PaymentFrameworkAutoConfiguration`
- Add a new delivery type by implementing a new strategy — do NOT add `if (type == REST)` / `else if (type == MESSAGING)` in `DeliveryService`
- Add a new `PaymentMessageHandler` by implementing the interface — do NOT modify the framework's handler dispatch logic

**Check:** If adding a new technology (e.g. Redis) requires editing existing, tested classes, the design violates Open/Closed.

### L — Liskov Substitution

**Rule:** Any implementation of an interface must be substitutable without the caller noticing.

All `ProcessedEventRepository` implementations (JPA, MongoDB, Redis) must:
- Return identical results for the same input
- Throw the same exceptions for the same error conditions
- Have the same transactional semantics (atomicity guarantees)
- Never introduce additional preconditions (e.g. "only works if connection pool > 5")

**Check:** If a test written against `JpaProcessedEventRepository` would fail when run against `MongoProcessedEventRepository` (for the same scenario), Liskov is violated.

### I — Interface Segregation

**Rule:** Clients should not depend on methods they don't use. Keep interfaces small and focused.

The framework already follows this:
- `ProcessedEventRepository` — lifecycle of `ProcessedEvent` only
- `OutboxRepository` — lifecycle of `OutboxEntry` only
- `PaymentMessageHandler<T>` — business logic only (no infrastructure methods)

**Do NOT** combine them into a `PaymentRepository` mega-interface. If you find yourself implementing a method as `throw new UnsupportedOperationException()`, the interface is too broad.

**Check:** If a new adapter implements an interface but has to throw `UnsupportedOperationException` for some methods, split the interface.

### D — Dependency Inversion

**Rule:** High-level modules depend on abstractions, not concretions.

```java
// Wrong — IdempotencyService depends on a concrete class
public class IdempotencyService {
    private final JpaProcessedEventRepository repository; // concrete JPA impl
}

// Right — depends on the abstraction
public class IdempotencyService {
    private final ProcessedEventRepository repository; // interface
}
```

All dependencies must be injected via constructor (not `@Autowired` field injection):
```java
// Wrong
@Autowired
private ProcessedEventRepository repository;

// Right
private final ProcessedEventRepository repository;

public IdempotencyService(ProcessedEventRepository repository, ...) {
    this.repository = Objects.requireNonNull(repository, "repository must not be null");
}
```

**Check before writing a new class:**
- [ ] Can I describe this class's job without using "and"?
- [ ] Do I depend on an interface, not a concrete class?
- [ ] If I add a new adapter/technology, do I need to touch any existing class?
- [ ] Could a consumer swap my implementation with another without noticing?

---

## Part 3 — BDD Test Structure (Given-When-Then)

All tests in this project follow BDD structure. Every test has exactly three logical sections.

### Two valid styles

**Style 1 — `@DisplayName` + `@Nested` (preferred for complex scenarios with multiple related cases)**

```java
@DisplayName("OutboxProcessor")
class OutboxProcessorTest {

    @Nested
    @DisplayName("given entries in RETRY_PENDING state with a past nextRetryAt")
    class GivenRetryPendingEntriesDue {

        @Test
        @DisplayName("when the processor polls, then those entries are picked up for delivery")
        void whenPolls_thenRetryPendingEntriesAreDelivered() {
            // Given
            OutboxEntry entry = buildRetryPendingEntry(nextRetryAt: Instant.now().minusSeconds(1));
            when(outboxRepository.findReadyForProcessing(anyInt())).thenReturn(List.of(entry));

            // When
            processor.processBatch();

            // Then
            verify(deliveryService).deliver(entry);
        }
    }
}
```

**Style 2 — method name encodes Given-When-Then (for simple, standalone unit tests)**

```java
// Pattern: given{Context}_when{Action}_then{Outcome}
@Test
void givenMaxRetriesReached_whenRecordFailure_thenStatusIsPermanentlyFailed() { ... }

@Test
void givenExpiredLock_whenFindExpiredLocks_thenReturnsEntryForRecovery() { ... }

@Test
void givenNullPayload_whenValidate_thenReturnsFalse() { ... }
```

### Non-negotiable rules

1. **One When per test.** If you have two `// When` sections, split into two tests.
2. **Assertions last.** Never interleave `assertThat` calls with setup code.
3. **AssertJ only.** Never use `assertTrue`, `assertEquals`, `assertNull` from JUnit — use AssertJ's fluent API:
   ```java
   // Wrong
   assertTrue(result.isDuplicate());
   assertEquals("txn-001", event.getIdempotencyKey());

   // Right
   assertThat(result.isDuplicate()).isTrue();
   assertThat(event.getIdempotencyKey()).isEqualTo("txn-001");
   ```
4. **Descriptive failure messages** on non-obvious assertions:
   ```java
   assertThat(successCount.get())
       .as("exactly one thread should acquire the lock in a concurrent scenario")
       .isEqualTo(1);
   ```
5. **One logical concept per test.** One reason to fail. Don't test both the happy path and error path in the same test method.
6. **No `Thread.sleep()` in tests.** Use `CountDownLatch`, `CompletableFuture.get()`, or `Awaitility` for async scenarios.

---

## Part 4 — Design Patterns in This Framework

### Patterns in use — extend, don't reinvent

Before introducing a new pattern, check if an existing one already covers the need.

**Builder** — `ProcessedEvent`, `OutboxEntry`, `OutboxPayload`
- Use for all domain models with more than 2 fields
- Never add a constructor with more than 2 params to a domain model — add a field to the existing Builder
- Required fields go in the builder's constructor; optional fields use `builder.field(value)`

**Adapter** — `JpaProcessedEventRepository` implements `ProcessedEventRepository`
- Every new persistence technology follows this identical pattern: `@Component` class wrapping a technology-specific store, with `toModel()` / `toEntity()` converters
- The adapter's public methods speak the domain language; the technology-specific store speaks the persistence language

**State Machine** — `PaymentState.transitionTo()`
- Adding a new state: update `canTransitionTo()`, `isTerminal()`, and `isRetryable()` in the same commit
- Never bypass the state machine. Even in admin/fix-up code, use `transitionTo()`

**Repository** — `ProcessedEventRepository`, `OutboxRepository`
- A new persistence technology creates a new implementation of the existing interfaces
- Never create a new repository interface for the same data (would break Liskov)

**Strategy** — `DeliveryType` (REST vs MESSAGING) in `OutboxPayload`
- Adding a new delivery mechanism = add a new strategy implementation
- Never add `if (type == REST) { ... } else if (type == MESSAGING) { ... } else if (type == NEW) { ... }` in `DeliveryService` — that violates Open/Closed

**Template Method** — `PaymentMessageHandler<T>` default methods (`onDuplicateDetected`, `onPermanentFailure`, `onDeliverySuccess`, `validate`)
- Adding a new hook: add a `default` method with a sensible no-op default — never an abstract method (would break existing implementations)

### When to introduce a new pattern

| Pattern | Introduce when... | Do NOT introduce when... |
|---------|------------------|--------------------------|
| **Observer / Event** | Multiple independent components react to the same state change | Only one consumer needs to know about the change |
| **Decorator** | Cross-cutting concerns (rate limiting, caching, audit) must wrap an existing interface without modifying it | You just want to add a method to an existing class |
| **Factory Method** | Object creation requires complex conditional logic | A simple constructor or Builder suffices |
| **Chain of Responsibility** | Multiple handlers may or may not process a request, and the set changes | The handler set is fixed at compile time |

### Anti-patterns to avoid

- **Singleton via static** — use Spring `@Component` / `@Bean` instead
- **Service Locator** (`ApplicationContext.getBean(...)`) — use constructor injection instead
- **God class** — a single class with >500 lines or >20 methods is accumulating multiple responsibilities
- **Anemic domain model** — `ProcessedEvent` with only getters/setters and all logic in a service. Domain objects should carry behaviour (`canRetry()`, `recordFailure()`, `markCompleted()`)
- **Primitive obsession** — don't pass raw `String` idempotencyKey everywhere when a value object `IdempotencyKey` would make intent clearer in new code

---

## Part 5 — Java 17 Features

This project requires Java 17. Use modern language features — do not write Java 8-style code.

### Records

**Use `record` for any data-carrying type that:**
- Has only final fields (immutable)
- Carries no business logic beyond computed accessors
- Would otherwise be a class with all-final fields, a constructor, and only getters

**Examples in this project:**

| Type | Why it is a record |
|------|--------------------|
| `IdempotencyService.ProcessingResult` | Pure result carrier — success flag, event, outbox entry |
| `DeliveryService.DeliveryResult` | Pure result carrier — success flag, status code, error, duration |
| `OutboxProcessor.ProcessorStatus` | Pure snapshot — running, enabled, counts |
| `IdempotencyKeyExtractor.RootObject` | Value object — payload + headers for SpEL context |

**How to write records in this project:**

```java
// Basic form — canonical constructor is generated
public record DeliveryResult(
        boolean success,
        boolean permanentFailure,
        String error,
        Integer httpStatus,
        long durationMillis) {

    // Static factory methods for semantic construction
    public static DeliveryResult success(long durationMillis) {
        return new DeliveryResult(true, false, null, 200, durationMillis);
    }

    public static DeliveryResult failure(String error, Integer httpStatus) {
        return new DeliveryResult(false, false, error, httpStatus, 0);
    }

    // is/get-prefixed aliases — keeps call sites readable (result.isSuccess() reads better than result.success())
    public boolean isSuccess() { return success; }
    public boolean isPermanentFailure() { return permanentFailure; }
    public String getError() { return error; }
}
```

**SpEL caveat — always add explicit JavaBeans getters to records used as SpEL root objects.**

SpEL resolves `payload.transactionId` by calling `getTransactionId()` (JavaBeans convention). Records generate `transactionId()`, not `getTransactionId()`. A record without explicit `getX()` methods will silently fail SpEL resolution.

```java
// Wrong — SpEL cannot resolve payload.field or headers['key']
public record RootObject(Object payload, Map<String, Object> headers) {}

// Right — explicit JavaBeans getters alongside record accessors
public record RootObject(Object payload, Map<String, Object> headers) {
    public Object getPayload() { return payload; }
    public Map<String, Object> getHeaders() { return headers; }
}
```

**When NOT to use a record:**
- The type is mutable (has setters or reassignable fields) — use a class
- The type is a JPA `@Entity` or MongoDB `@Document` — persistence frameworks require mutable classes with no-arg constructors
- The type needs to extend a class (records can only extend `Object`)

### Text Blocks

Use text blocks for multi-line strings (SQL, JSON templates, log messages):

```java
// Wrong
String sql = "SELECT * FROM outbox_entries " +
             "WHERE status = 'PENDING' " +
             "AND next_retry_at <= CURRENT_TIMESTAMP";

// Right
String sql = """
        SELECT * FROM outbox_entries
        WHERE status = 'PENDING'
        AND next_retry_at <= CURRENT_TIMESTAMP
        """;
```

JPQL `@Query` annotations already use text blocks throughout this project — follow that pattern.

### Pattern Matching for `instanceof`

```java
// Wrong — Java 8 style
if (message instanceof TextMessage) {
    TextMessage textMessage = (TextMessage) message;
    body = textMessage.getText();
}

// Right — Java 16+ pattern matching
if (message instanceof TextMessage textMessage) {
    body = textMessage.getText();
}
```

### Switch Expressions

Use switch expressions (not statements) when assigning a value based on an enum:

```java
// Wrong — switch statement with redundant assignments
String method;
switch (deliveryType) {
    case REST: method = "POST"; break;
    case MESSAGING: method = "SEND"; break;
    default: method = "UNKNOWN";
}

// Right — switch expression
String method = switch (deliveryType) {
    case REST -> "POST";
    case MESSAGING -> "SEND";
};
```
