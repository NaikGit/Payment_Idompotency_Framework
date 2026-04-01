package com.dev.payments.framework.kafka;

import com.dev.payments.framework.model.OutboxEntry;
import com.dev.payments.framework.model.OutboxPayload;
import com.dev.payments.framework.model.ProcessedEvent;
import com.dev.payments.framework.repository.OutboxRepository;
import com.dev.payments.framework.repository.ProcessedEventRepository;
import com.dev.payments.framework.service.IdempotencyService;
import com.dev.payments.framework.state.PaymentState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for KafkaPaymentConsumer using EmbeddedKafka.
 *
 * The tests wire a concrete TestPaymentConsumer subclass against real embedded Kafka,
 * with mocked repositories (so no database is needed). This validates that:
 *
 *  - a first-time message is processed and routed through IdempotencyService
 *  - a duplicate message is detected and skipped without calling processPayment() again
 *  - an invalid message (validate() returns false) is acknowledged but not processed
 *  - a malformed JSON message is acknowledged gracefully without crashing the consumer
 *  - the onDuplicateDetected callback fires for duplicate messages
 *  - every message is acknowledged (never left unacked)
 */
@SpringBootTest(classes = KafkaPaymentConsumerTest.TestConfig.class)
@EmbeddedKafka(
        partitions = 1,
        topics = {KafkaPaymentConsumerTest.TOPIC},
        brokerProperties = {
                "listeners=PLAINTEXT://localhost:${kafka.port:19092}",
                "port=${kafka.port:19092}"
        })
@DirtiesContext
@ActiveProfiles("test")
@DisplayName("KafkaPaymentConsumer")
class KafkaPaymentConsumerTest {

    static final String TOPIC = "payments.test";

    // -------------------------------------------------------------------------
    // Test infrastructure
    // -------------------------------------------------------------------------

    /**
     * Minimal Spring Boot test configuration — only what the consumer needs.
     * No auto-configuration, no database. Repositories are mocked.
     */
    @org.springframework.boot.test.context.TestConfiguration
    static class TestConfig {

        @org.springframework.context.annotation.Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().registerModule(new JavaTimeModule());
        }

        @org.springframework.context.annotation.Bean
        SimpleMeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @org.springframework.context.annotation.Bean
        ProcessedEventRepository processedEventRepository() {
            ProcessedEventRepository repo = mock(ProcessedEventRepository.class);
            when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(repo.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
            when(repo.existsByIdempotencyKey(anyString())).thenReturn(false);
            return repo;
        }

        @org.springframework.context.annotation.Bean
        OutboxRepository outboxRepository() {
            OutboxRepository repo = mock(OutboxRepository.class);
            when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
            return repo;
        }

        @org.springframework.context.annotation.Bean
        IdempotencyService idempotencyService(ProcessedEventRepository processedEventRepository,
                                              OutboxRepository outboxRepository,
                                              ObjectMapper objectMapper,
                                              SimpleMeterRegistry meterRegistry) {
            return new IdempotencyService(processedEventRepository, outboxRepository, objectMapper, meterRegistry);
        }
    }

    // -------------------------------------------------------------------------
    // Test payload and consumer
    // -------------------------------------------------------------------------

    record PaymentEvent(String transactionId, int amount) {}

    /**
     * Concrete consumer for testing. Tracks how many times processPayment() was called
     * and which duplicate callbacks fired, so tests can assert on them.
     */
    static class TestPaymentConsumer extends KafkaPaymentConsumer<PaymentEvent> {

        final AtomicInteger processPaymentCallCount = new AtomicInteger(0);
        final List<String> duplicateCallbacks = new ArrayList<>();
        boolean shouldValidate = true;

        TestPaymentConsumer(IdempotencyService idempotencyService,
                            ObjectMapper objectMapper,
                            SimpleMeterRegistry meterRegistry) {
            super(idempotencyService, objectMapper, meterRegistry);
        }

        @Override
        protected String extractIdempotencyKey(ConsumerRecord<String, String> record, PaymentEvent payload) {
            return payload.transactionId();
        }

        @Override
        protected String getDestination() {
            return "LEDGER";
        }

        @Override
        public Class<PaymentEvent> getPayloadType() {
            return PaymentEvent.class;
        }

        @Override
        public OutboxPayload processPayment(PaymentEvent event) {
            processPaymentCallCount.incrementAndGet();
            return OutboxPayload.restPost("https://ledger.internal/api/v1/transactions",
                    Map.of("amount", event.amount()));
        }

        @Override
        public boolean validate(PaymentEvent payload) {
            return shouldValidate;
        }

        @Override
        public void onDuplicateDetected(PaymentEvent payload, String idempotencyKey) {
            duplicateCallbacks.add(idempotencyKey);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private TestPaymentConsumer consumer;
    private ProcessedEventRepository processedEventRepository;
    private OutboxRepository outboxRepository;
    private ObjectMapper objectMapper;

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        processedEventRepository = mock(ProcessedEventRepository.class);
        outboxRepository = mock(OutboxRepository.class);

        when(processedEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(outboxRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        IdempotencyService localService = new IdempotencyService(
                processedEventRepository, outboxRepository, objectMapper, new SimpleMeterRegistry());

        consumer = new TestPaymentConsumer(localService, objectMapper, new SimpleMeterRegistry());
    }

    private ConsumerRecord<String, String> record(String key, String json) {
        return new ConsumerRecord<>(TOPIC, 0, 0L, key, json);
    }

    private Acknowledgment noopAck() {
        return mock(Acknowledgment.class);
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("given a payment event received for the first time")
    class GivenFirstTimeMessage {

        @Test
        @DisplayName("when consume is called, then processPayment is invoked once")
        void whenConsume_thenProcessPaymentInvokedOnce() throws Exception {
            // Given
            when(processedEventRepository.existsByIdempotencyKey("txn-kafka-001")).thenReturn(false);
            when(processedEventRepository.findByIdempotencyKey("txn-kafka-001")).thenReturn(Optional.empty());

            String json = objectMapper.writeValueAsString(new PaymentEvent("txn-kafka-001", 100));

            // When
            consumer.consume(record("txn-kafka-001", json), noopAck());

            // Then
            assertThat(consumer.processPaymentCallCount.get())
                    .as("processPayment must be called once for a new message")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("when consume is called, then the message is acknowledged")
        void whenConsume_thenMessageIsAcknowledged() throws Exception {
            // Given
            when(processedEventRepository.existsByIdempotencyKey("txn-kafka-002")).thenReturn(false);
            when(processedEventRepository.findByIdempotencyKey("txn-kafka-002")).thenReturn(Optional.empty());

            String json = objectMapper.writeValueAsString(new PaymentEvent("txn-kafka-002", 200));
            Acknowledgment ack = noopAck();

            // When
            consumer.consume(record("txn-kafka-002", json), ack);

            // Then
            org.mockito.Mockito.verify(ack).acknowledge();
        }

        @Test
        @DisplayName("when consume is called, then no duplicate callbacks fire")
        void whenConsume_thenNoDuplicateCallbacks() throws Exception {
            // Given
            when(processedEventRepository.existsByIdempotencyKey("txn-kafka-003")).thenReturn(false);
            when(processedEventRepository.findByIdempotencyKey("txn-kafka-003")).thenReturn(Optional.empty());

            String json = objectMapper.writeValueAsString(new PaymentEvent("txn-kafka-003", 300));

            // When
            consumer.consume(record("txn-kafka-003", json), noopAck());

            // Then
            assertThat(consumer.duplicateCallbacks).isEmpty();
        }
    }

    @Nested
    @DisplayName("given a payment event received a second time (duplicate)")
    class GivenDuplicateMessage {

        @Test
        @DisplayName("when consume is called for the duplicate, then processPayment is NOT called again")
        void whenDuplicateConsumed_thenProcessPaymentNotCalledAgain() throws Exception {
            // Given — simulate the key already exists
            when(processedEventRepository.existsByIdempotencyKey("txn-dup-kafka-001")).thenReturn(true);
            String json = objectMapper.writeValueAsString(new PaymentEvent("txn-dup-kafka-001", 100));

            // When
            consumer.consume(record("txn-dup-kafka-001", json), noopAck());

            // Then
            assertThat(consumer.processPaymentCallCount.get())
                    .as("processPayment must NOT be called for a duplicate message")
                    .isZero();
        }

        @Test
        @DisplayName("when consume is called for the duplicate, then the message is still acknowledged")
        void whenDuplicateConsumed_thenMessageIsStillAcknowledged() throws Exception {
            // Given
            when(processedEventRepository.existsByIdempotencyKey("txn-dup-kafka-002")).thenReturn(true);
            String json = objectMapper.writeValueAsString(new PaymentEvent("txn-dup-kafka-002", 100));
            Acknowledgment ack = noopAck();

            // When
            consumer.consume(record("txn-dup-kafka-002", json), ack);

            // Then
            org.mockito.Mockito.verify(ack).acknowledge();
        }

        @Test
        @DisplayName("when consume is called for the duplicate, then onDuplicateDetected callback fires")
        void whenDuplicateConsumed_thenOnDuplicateDetectedFires() throws Exception {
            // Given
            when(processedEventRepository.existsByIdempotencyKey("txn-dup-kafka-003")).thenReturn(true);
            String json = objectMapper.writeValueAsString(new PaymentEvent("txn-dup-kafka-003", 100));

            // When
            consumer.consume(record("txn-dup-kafka-003", json), noopAck());

            // Then
            assertThat(consumer.duplicateCallbacks)
                    .as("onDuplicateDetected must fire for a duplicate message")
                    .containsExactly("txn-dup-kafka-003");
        }
    }

    @Nested
    @DisplayName("given a message that fails payload validation")
    class GivenInvalidPayload {

        @Test
        @DisplayName("when consume is called, then processPayment is NOT called")
        void whenInvalidPayload_thenProcessPaymentNotCalled() throws Exception {
            // Given
            consumer.shouldValidate = false;
            when(processedEventRepository.existsByIdempotencyKey("txn-invalid-001")).thenReturn(false);
            String json = objectMapper.writeValueAsString(new PaymentEvent("txn-invalid-001", -1));

            // When
            consumer.consume(record("txn-invalid-001", json), noopAck());

            // Then
            assertThat(consumer.processPaymentCallCount.get())
                    .as("processPayment must not be called for an invalid payload")
                    .isZero();
        }

        @Test
        @DisplayName("when consume is called with an invalid payload, then message is still acknowledged")
        void whenInvalidPayload_thenMessageIsAcknowledged() throws Exception {
            // Given
            consumer.shouldValidate = false;
            when(processedEventRepository.existsByIdempotencyKey("txn-invalid-002")).thenReturn(false);
            String json = objectMapper.writeValueAsString(new PaymentEvent("txn-invalid-002", -1));
            Acknowledgment ack = noopAck();

            // When
            consumer.consume(record("txn-invalid-002", json), ack);

            // Then
            org.mockito.Mockito.verify(ack).acknowledge();
        }
    }

    @Nested
    @DisplayName("given a malformed (non-JSON) Kafka message")
    class GivenMalformedMessage {

        @Test
        @DisplayName("when consume is called, then consumer does not crash and still acknowledges")
        void whenMalformedMessage_thenConsumerDoesNotCrash() {
            // Given — garbage payload that cannot be deserialized
            Acknowledgment ack = noopAck();

            // When / Then — no exception propagated
            consumer.consume(record("txn-bad-001", "NOT_JSON{{{"), ack);

            org.mockito.Mockito.verify(ack).acknowledge();
            assertThat(consumer.processPaymentCallCount.get()).isZero();
        }
    }

    @Nested
    @DisplayName("given processPayment throws an exception")
    class GivenProcessPaymentThrows {

        @Test
        @DisplayName("when processPayment throws, then message is still acknowledged and onPermanentFailure fires")
        void whenProcessPaymentThrows_thenMessageAcknowledgedAndCallbackFires() throws Exception {
            // Given — consumer whose processPayment throws
            List<String> permanentFailures = new ArrayList<>();
            IdempotencyService localService = new IdempotencyService(
                    processedEventRepository, outboxRepository, objectMapper, new SimpleMeterRegistry());

            KafkaPaymentConsumer<PaymentEvent> throwingConsumer = new KafkaPaymentConsumer<>(
                    localService, objectMapper, new SimpleMeterRegistry()) {
                @Override
                protected String extractIdempotencyKey(ConsumerRecord<String, String> record, PaymentEvent payload) {
                    return payload.transactionId();
                }

                @Override
                protected String getDestination() { return "LEDGER"; }

                @Override
                public Class<PaymentEvent> getPayloadType() { return PaymentEvent.class; }

                @Override
                public OutboxPayload processPayment(PaymentEvent event) throws Exception {
                    throw new RuntimeException("downstream unavailable");
                }

                @Override
                public void onPermanentFailure(PaymentEvent payload, String idempotencyKey, String lastError) {
                    permanentFailures.add(idempotencyKey);
                }
            };

            when(processedEventRepository.existsByIdempotencyKey("txn-throw-001")).thenReturn(false);
            String json = objectMapper.writeValueAsString(new PaymentEvent("txn-throw-001", 100));
            Acknowledgment ack = noopAck();

            // When
            throwingConsumer.consume(record("txn-throw-001", json), ack);

            // Then
            org.mockito.Mockito.verify(ack).acknowledge();
            assertThat(permanentFailures).containsExactly("txn-throw-001");
        }
    }
}
