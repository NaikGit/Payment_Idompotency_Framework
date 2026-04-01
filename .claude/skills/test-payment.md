---
name: test-payment
description: Write tests for payment-framework components. Covers handler unit tests, repository adapter tests, locking/concurrency, state machine, and embedded integration tests. Uses BDD Given-When-Then structure with JUnit 5. No Docker/TestContainers — uses H2, embedded-kafka, Flapdoodle MongoDB, or mocks depending on adapter type.
---

# Write Payment Framework Tests

Determine what the user wants to test, then follow the matching section below.

**Ask the user:**
1. What component are they testing? (handler, repository adapter, state machine, locking, end-to-end)
2. Which class or module is under test?
3. Are they writing new tests from scratch, or adding tests to cover a bug fix?

All tests use **JUnit 5**, **Mockito** (via `spring-boot-starter-test`), and **AssertJ** assertions. All tests follow BDD Given-When-Then structure — see `/java-coding-standards` Part 3 for the naming and structure rules.

---

## Section A: Handler Unit Tests

Test the business logic inside a `PaymentMessageHandler<T>` implementation. Mock all dependencies — do not start Spring context.

### A1 — Testing processPayment() logic

```java
@ExtendWith(MockitoExtension.class)
class PaymentEventHandlerTest {

    @Mock
    private PaymentTransformer transformer;

    private PaymentEventHandler handler;

    @BeforeEach
    void setUp() {
        handler = new PaymentEventHandler(transformer);
    }

    @Test
    @DisplayName("given a valid payment event, when processPayment is called, then returns OutboxPayload with correct endpoint and body")
    void givenValidEvent_whenProcessPayment_thenReturnsCorrectOutboxPayload() throws Exception {
        // Given
        PaymentEvent event = buildValidEvent("txn-001");
        LedgerRequest ledgerRequest = buildLedgerRequest("txn-001");
        when(transformer.toLedgerRequest(event)).thenReturn(ledgerRequest);

        // When
        OutboxPayload payload = handler.processPayment(event);

        // Then
        assertThat(payload.getEndpoint())
            .as("endpoint should route to ledger API")
            .isEqualTo("https://ledger.internal/api/v1/transactions");
        assertThat(payload.getBody()).isEqualTo(ledgerRequest);
        assertThat(payload.getMethod()).isEqualTo(HttpMethod.POST);
    }

    @Test
    @DisplayName("given transformer throws, when processPayment is called, then exception propagates (no swallowing)")
    void givenTransformerFails_whenProcessPayment_thenExceptionPropagates() {
        // Given
        PaymentEvent event = buildValidEvent("txn-002");
        when(transformer.toLedgerRequest(event)).thenThrow(new RuntimeException("Mapping failed"));

        // When / Then
        assertThatThrownBy(() -> handler.processPayment(event))
            .isInstanceOf(RuntimeException.class)
            .hasMessage("Mapping failed");
    }

    @Test
    @DisplayName("given event with null transactionId, when validate is called, then returns false")
    void givenNullTransactionId_whenValidate_thenReturnsFalse() {
        // Given
        PaymentEvent event = new PaymentEvent();
        event.setTransactionId(null);

        // When
        boolean valid = handler.validate(event);

        // Then
        assertThat(valid).isFalse();
    }

    @Test
    @DisplayName("given a valid event, when validate is called, then returns true")
    void givenValidEvent_whenValidate_thenReturnsTrue() {
        // Given
        PaymentEvent event = buildValidEvent("txn-003");

        // Then
        assertThat(handler.validate(event)).isTrue();
    }
}
```

### A2 — Testing IdempotencyService (mocked repositories)

```java
@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    @Mock private ProcessedEventRepository processedEventRepository;
    @Mock private OutboxRepository outboxRepository;
    @Mock private ObjectMapper objectMapper;
    @Mock private MeterRegistry meterRegistry;

    private IdempotencyService service;

    @BeforeEach
    void setUp() {
        // MeterRegistry builder chain must return non-null stubs
        Counter counter = mock(Counter.class);
        Timer timer = mock(Timer.class);
        when(meterRegistry.counter(anyString(), any(String[].class))).thenReturn(counter);
        when(meterRegistry.timer(anyString(), any(String[].class))).thenReturn(timer);

        service = new IdempotencyService(
            processedEventRepository, outboxRepository, objectMapper, meterRegistry);
    }

    @Nested
    @DisplayName("given a key that was already processed")
    class GivenDuplicateKey {

        @Test
        @DisplayName("when processMessage is called, then returns duplicate result and creates no outbox entry")
        void whenProcessMessage_thenReturnsDuplicateWithNoOutboxEntry() {
            // Given
            String key = "txn-dup-001";
            ProcessedEvent existing = buildEvent(key, PaymentState.COMPLETED);
            when(processedEventRepository.findByIdempotencyKey(key))
                .thenReturn(Optional.of(existing));

            // When
            var result = service.processMessage(key, "SOURCE_Q", "LEDGER", buildPayload());

            // Then
            assertThat(result.isDuplicate()).isTrue();
            assertThat(result.getEvent().getIdempotencyKey()).isEqualTo(key);
            verify(outboxRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("given a new, unseen key")
    class GivenNewKey {

        @Test
        @DisplayName("when processMessage is called, then saves ProcessedEvent in PROCESSING state and creates OutboxEntry")
        void whenProcessMessage_thenSavesBothRecordsAtomically() throws Exception {
            // Given
            String key = "txn-new-001";
            when(processedEventRepository.findByIdempotencyKey(key)).thenReturn(Optional.empty());
            when(processedEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(outboxRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(objectMapper.writeValueAsString(any())).thenReturn("{\"amount\":100}");

            // When
            var result = service.processMessage(key, "SOURCE_Q", "LEDGER", buildPayload());

            // Then
            assertThat(result.isSuccess()).isTrue();
            verify(processedEventRepository).save(argThat(e ->
                e.getIdempotencyKey().equals(key) && e.getState() == PaymentState.PROCESSING));
            verify(outboxRepository).save(argThat(e ->
                e.getIdempotencyKey().equals(key)));
        }
    }
}
```

---

## Section B: Repository Adapter Tests (H2 / @DataJpaTest)

Use `@DataJpaTest` with H2 in-memory for JPA adapter tests. Fast and no Docker required.

```java
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY) // forces H2
class JpaProcessedEventRepositoryTest {

    @Autowired
    private ProcessedEventJpaRepository jpaRepository;

    private JpaProcessedEventRepository repository;

    @BeforeEach
    void setUp() {
        repository = new JpaProcessedEventRepository(jpaRepository);
    }

    @Test
    @DisplayName("given a saved ProcessedEvent, when findByIdempotencyKey, then returns event with all fields preserved")
    void givenSavedEvent_whenFindByIdempotencyKey_thenAllFieldsPreserved() {
        // Given
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        ProcessedEvent event = ProcessedEvent.builder()
            .idempotencyKey("txn-rtt-001")
            .state(PaymentState.PROCESSING)
            .source("QUEUE_A")
            .destination("LEDGER")
            .receivedAt(now)
            .processedBy("node-1")
            .build();
        repository.save(event);

        // When
        Optional<ProcessedEvent> found = repository.findByIdempotencyKey("txn-rtt-001");

        // Then
        assertThat(found).isPresent();
        assertThat(found.get().getState()).isEqualTo(PaymentState.PROCESSING);
        assertThat(found.get().getSource()).isEqualTo("QUEUE_A");
        assertThat(found.get().getDestination()).isEqualTo("LEDGER");
        assertThat(found.get().getProcessedBy()).isEqualTo("node-1");
    }

    @Test
    @DisplayName("given no event exists, when existsByIdempotencyKey, then returns false")
    void givenNoEvent_whenExistsByKey_thenReturnsFalse() {
        assertThat(repository.existsByIdempotencyKey("txn-nonexistent")).isFalse();
    }

    @Test
    @DisplayName("given event in PROCESSING state, when updateState to FAILED, then state changes and lastError is set")
    void givenProcessingEvent_whenUpdateStateToFailed_thenStateChanges() {
        // Given
        saveEventWithState("txn-upd-001", PaymentState.PROCESSING);

        // When
        boolean updated = repository.updateState("txn-upd-001", PaymentState.FAILED, "HTTP 503 Service Unavailable");

        // Then
        assertThat(updated).isTrue();
        ProcessedEvent event = repository.findByIdempotencyKey("txn-upd-001").get();
        assertThat(event.getState()).isEqualTo(PaymentState.FAILED);
        assertThat(event.getLastError()).isEqualTo("HTTP 503 Service Unavailable");
    }

    @Test
    @DisplayName("given event in SENDING state, when markCompleted, then state is COMPLETED and completedAt is set")
    void givenSendingEvent_whenMarkCompleted_thenCompletedAtIsSet() {
        // Given
        saveEventWithState("txn-cmp-001", PaymentState.SENDING);

        // When
        boolean result = repository.markCompleted("txn-cmp-001");

        // Then
        assertThat(result).isTrue();
        ProcessedEvent event = repository.findByIdempotencyKey("txn-cmp-001").get();
        assertThat(event.getState()).isEqualTo(PaymentState.COMPLETED);
        assertThat(event.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("given old COMPLETED events, when deleteCompletedBefore, then only old events are removed")
    void givenOldCompletedEvents_whenDeleteCompletedBefore_thenOldEventsRemoved() {
        // Given
        Instant longAgo = Instant.now().minus(30, ChronoUnit.DAYS);
        saveCompletedEventAt("txn-old-001", longAgo.minusSeconds(1));
        saveCompletedEventAt("txn-recent-001", Instant.now().minusSeconds(60));

        // When
        int deleted = repository.deleteCompletedBefore(longAgo);

        // Then
        assertThat(deleted).isEqualTo(1);
        assertThat(repository.existsByIdempotencyKey("txn-old-001")).isFalse();
        assertThat(repository.existsByIdempotencyKey("txn-recent-001")).isTrue();
    }

    // Helpers
    private void saveEventWithState(String key, PaymentState state) {
        repository.save(ProcessedEvent.builder()
            .idempotencyKey(key).state(state).receivedAt(Instant.now()).build());
    }

    private void saveCompletedEventAt(String key, Instant completedAt) {
        ProcessedEvent event = ProcessedEvent.builder()
            .idempotencyKey(key).state(PaymentState.COMPLETED)
            .receivedAt(completedAt.minusSeconds(10)).build();
        repository.save(event);
        repository.markCompleted(key);
    }
}
```

---

## Section C: Locking and Concurrency Tests

These tests verify `tryLock()` atomicity and `releaseLock()` ownership enforcement.

```java
@DataJpaTest
class OutboxLockingTest {

    // Wire up JpaOutboxRepository or the adapter under test

    @Test
    @DisplayName("given a pending entry, when 10 threads concurrently try to lock it, then exactly one succeeds")
    void givenPendingEntry_whenConcurrentLockAttempts_thenExactlyOneSucceeds()
        throws InterruptedException {
        // Given
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
                    startLatch.await(); // all threads start simultaneously
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

        // When
        startLatch.countDown(); // release all threads at once
        doneLatch.await(5, TimeUnit.SECONDS);

        // Then
        assertThat(successCount.get())
            .as("exactly one thread should win the lock")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("given node-A holds the lock, when node-B attempts release, then lock is NOT released")
    void givenLockHeldByNodeA_whenNodeBAttemptsRelease_thenLockRemainsHeld() {
        // Given
        String entryId = createPendingEntry();
        outboxRepository.tryLock(entryId, "node-A", 60);

        // When
        boolean released = outboxRepository.releaseLock(entryId, "node-B");

        // Then
        assertThat(released)
            .as("node-B must not be able to release node-A's lock")
            .isFalse();
        // Verify the entry is still locked by node-A
        OutboxEntry entry = outboxRepository.findById(entryId).get();
        assertThat(entry.getLockedBy()).isEqualTo("node-A");
    }

    @Test
    @DisplayName("given an entry with an expired lock, when findExpiredLocks is called, then the entry is returned for recovery")
    void givenExpiredLock_whenFindExpiredLocks_thenEntryReturnedForRecovery() {
        // Given
        String entryId = createEntryWithExpiredLock();

        // When
        List<OutboxEntry> expired = outboxRepository.findExpiredLocks();

        // Then
        assertThat(expired).extracting(OutboxEntry::getId).contains(entryId);
    }
}
```

---

## Section D: State Machine Tests

```java
class PaymentStateTest {

    @ParameterizedTest(name = "{0} → {1} should succeed")
    @MethodSource("validTransitions")
    void givenValidTransition_whenTransitionTo_thenSucceeds(PaymentState from, PaymentState to) {
        // When
        PaymentState result = from.transitionTo(to);

        // Then
        assertThat(result).isEqualTo(to);
    }

    @ParameterizedTest(name = "COMPLETED → {0} should throw")
    @EnumSource(PaymentState.class)
    void givenCompletedState_whenTransitionToAnything_thenThrows(PaymentState target) {
        assertThatThrownBy(() -> PaymentState.COMPLETED.transitionTo(target))
            .isInstanceOf(InvalidStateTransitionException.class)
            .hasMessageContaining("COMPLETED");
    }

    @ParameterizedTest(name = "{0} → {1} should throw InvalidStateTransitionException")
    @MethodSource("invalidTransitions")
    void givenInvalidTransition_whenTransitionTo_thenThrows(PaymentState from, PaymentState to) {
        assertThatThrownBy(() -> from.transitionTo(to))
            .isInstanceOf(InvalidStateTransitionException.class);
    }

    static Stream<Arguments> validTransitions() {
        return Stream.of(
            Arguments.of(PaymentState.RECEIVED, PaymentState.PROCESSING),
            Arguments.of(PaymentState.PROCESSING, PaymentState.SENDING),
            Arguments.of(PaymentState.PROCESSING, PaymentState.FAILED),
            Arguments.of(PaymentState.SENDING, PaymentState.COMPLETED),
            Arguments.of(PaymentState.SENDING, PaymentState.FAILED),
            Arguments.of(PaymentState.FAILED, PaymentState.SENDING),
            Arguments.of(PaymentState.FAILED, PaymentState.COMPLETED)
        );
    }

    static Stream<Arguments> invalidTransitions() {
        return Stream.of(
            Arguments.of(PaymentState.RECEIVED, PaymentState.COMPLETED),
            Arguments.of(PaymentState.RECEIVED, PaymentState.SENDING),
            Arguments.of(PaymentState.PROCESSING, PaymentState.RECEIVED),
            Arguments.of(PaymentState.SENDING, PaymentState.RECEIVED)
        );
    }
}
```

---

## Section E: Embedded Integration Tests (no Docker required)

Use embedded infrastructure to test end-to-end behaviour without Docker. Choose the right embedded tool per adapter type.

### E1 — JPA/Oracle adapter: H2 + @SpringBootTest

H2 is the embedded substitute for Oracle in integration tests.

```java
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY) // forces H2
class JpaIdempotencyIntegrationTest {

    @Autowired
    private IdempotencyService idempotencyService;

    @Test
    @DisplayName("given a key processed once, when the same key is submitted again, then the second call is a duplicate no-op")
    void givenKeyProcessedOnce_whenSubmittedAgain_thenSecondIsDuplicate() {
        // Given
        OutboxPayload payload = OutboxPayload.restPost(
            "https://ledger/api/v1/payments", Map.of("amount", 100));

        // When
        var first = idempotencyService.processMessage("txn-e2e-001", "MQ", "LEDGER", payload);
        var second = idempotencyService.processMessage("txn-e2e-001", "MQ", "LEDGER", payload);

        // Then
        assertThat(first.isSuccess()).as("first submission should succeed").isTrue();
        assertThat(second.isDuplicate()).as("second with same key must be a no-op").isTrue();
    }
}
```

Place H2-compatible DDL in `src/test/resources/schema.sql` if the production schema uses Oracle-specific syntax.

### E2 — Kafka adapter: `@EmbeddedKafka`

Add `spring-kafka-test` to the module's test scope (it is in the Spring Boot BOM — no `<version>` needed):

```xml
<dependency>
    <groupId>org.springframework.kafka</groupId>
    <artifactId>spring-kafka-test</artifactId>
    <scope>test</scope>
</dependency>
```

```java
@SpringBootTest
@EmbeddedKafka(
    partitions = 1,
    topics = {"payment-events"},
    brokerProperties = {"listeners=PLAINTEXT://localhost:${kafka.port}", "port=${kafka.port}"}
)
class KafkaPaymentHandlerIntegrationTest {

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private IdempotencyService idempotencyService;

    @Test
    @DisplayName("given a payment event on Kafka, when consumed, then idempotency key is recorded")
    void givenPaymentEvent_whenConsumed_thenIdempotencyKeyRecorded() throws Exception {
        // Given
        String payload = "{\"transactionId\":\"txn-kafka-001\",\"amount\":500}";

        // When
        kafkaTemplate.send("payment-events", "txn-kafka-001", payload).get();
        // Allow consumer to process
        Thread.sleep(500);

        // Then
        assertThat(idempotencyService.isDuplicate("txn-kafka-001")).isTrue();
    }
}
```

Set `spring.kafka.bootstrap-servers` to `${spring.embedded.kafka.brokers}` in `src/test/resources/application-test.yml`.

### E3 — MongoDB adapter: Flapdoodle Embedded MongoDB

Add to the module's test scope (check the Spring Boot BOM for the current version):

```xml
<dependency>
    <groupId>de.flapdoodle.embed</groupId>
    <artifactId>de.flapdoodle.embed.mongo.spring30x</artifactId>
    <scope>test</scope>
</dependency>
```

```java
@DataMongoTest  // starts embedded MongoDB, no Spring context overhead
class MongoProcessedEventRepositoryTest {

    @Autowired
    private MongoTemplate mongoTemplate;

    private MongoProcessedEventRepository repository;

    @BeforeEach
    void setUp() {
        repository = new MongoProcessedEventRepository(mongoTemplate);
    }

    @Test
    @DisplayName("given a saved event, when findByIdempotencyKey, then all fields are preserved")
    void givenSavedEvent_whenFindByIdempotencyKey_thenAllFieldsPreserved() {
        // Given
        ProcessedEvent event = ProcessedEvent.builder()
            .idempotencyKey("txn-mongo-001")
            .state(PaymentState.PROCESSING)
            .source("QUEUE_A")
            .destination("LEDGER")
            .receivedAt(Instant.now())
            .build();
        repository.save(event);

        // When
        Optional<ProcessedEvent> found = repository.findByIdempotencyKey("txn-mongo-001");

        // Then
        assertThat(found).isPresent();
        assertThat(found.get().getState()).isEqualTo(PaymentState.PROCESSING);
    }
}
```

### E4 — IBM MQ adapter: mock the `JmsTemplate` / `MQConnectionFactory`

IBM MQ has no lightweight embedded option. Use Mockito to mock the JMS infrastructure:

```java
@ExtendWith(MockitoExtension.class)
class IbmMqPaymentListenerTest {

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private JmsTemplate jmsTemplate;

    private IbmMqPaymentListener listener;

    @BeforeEach
    void setUp() {
        listener = new IbmMqPaymentListener(idempotencyService, jmsTemplate);
    }

    @Test
    @DisplayName("given a valid MQ message, when onMessage is called, then idempotency service is invoked with the correct key")
    void givenValidMessage_whenOnMessage_thenIdempotencyServiceInvoked() throws Exception {
        // Given
        TextMessage message = mock(TextMessage.class);
        when(message.getText()).thenReturn("{\"transactionId\":\"txn-mq-001\"}");
        when(idempotencyService.processMessage(eq("txn-mq-001"), any(), any(), any()))
            .thenReturn(ProcessResult.success(buildEvent("txn-mq-001")));

        // When
        listener.onMessage(message);

        // Then
        verify(idempotencyService).processMessage(eq("txn-mq-001"), any(), any(), any());
    }
}
```

### E5 — Redis adapter: mock the `RedisTemplate` / Lettuce client

No embedded Redis is used. Mock the Redis client for unit/integration tests:

```java
@ExtendWith(MockitoExtension.class)
class RedisProcessedEventRepositoryTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private RedisProcessedEventRepository repository;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        repository = new RedisProcessedEventRepository(redisTemplate, new ObjectMapper());
    }

    @Test
    @DisplayName("given no entry in Redis, when existsByIdempotencyKey, then returns false")
    void givenNoEntry_whenExistsByKey_thenReturnsFalse() {
        // Given
        when(redisTemplate.hasKey("txn-redis-001")).thenReturn(false);

        // When / Then
        assertThat(repository.existsByIdempotencyKey("txn-redis-001")).isFalse();
    }
}

---

## Test Placement

Mirror source paths under `src/test/java/`:

```
src/main/java/com/bank/payments/framework/jpa/repository/JpaProcessedEventRepository.java
                                                ↕
src/test/java/com/bank/payments/framework/jpa/repository/JpaProcessedEventRepositoryTest.java
```

## Running Tests

```bash
# All tests in a module
mvn test -pl payment-framework-jpa

# Specific test class
mvn test -pl payment-framework-jpa -Dtest=JpaProcessedEventRepositoryTest

# All modules
mvn test
```

No Docker required — all infrastructure is embedded or mocked.
