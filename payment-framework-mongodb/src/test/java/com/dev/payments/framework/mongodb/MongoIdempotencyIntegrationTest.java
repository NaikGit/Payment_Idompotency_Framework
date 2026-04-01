package com.dev.payments.framework.mongodb;

import com.dev.payments.framework.model.OutboxPayload;
import com.dev.payments.framework.model.ProcessedEvent;
import com.dev.payments.framework.mongodb.repository.MongoOutboxRepository;
import com.dev.payments.framework.mongodb.repository.MongoProcessedEventRepository;
import com.dev.payments.framework.mongodb.repository.OutboxEntryMongoStore;
import com.dev.payments.framework.mongodb.repository.ProcessedEventMongoStore;
import com.dev.payments.framework.service.IdempotencyService;
import com.dev.payments.framework.state.PaymentState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test: real MongoProcessedEventRepository + real MongoOutboxRepository
 * against Flapdoodle embedded MongoDB + real IdempotencyService.
 *
 * This test verifies the idempotency guarantee end-to-end through the MongoDB persistence layer.
 * Unlike the JPA integration test, both repositories are fully wired — no mocks needed.
 */
@DataMongoTest
@ActiveProfiles("test")
@DisplayName("Idempotency integration (MongoDB + Flapdoodle)")
class MongoIdempotencyIntegrationTest {

    @Autowired
    private ProcessedEventMongoStore processedEventStore;

    @Autowired
    private OutboxEntryMongoStore outboxEntryStore;

    @Autowired
    private MongoTemplate mongoTemplate;

    private MongoProcessedEventRepository processedEventRepository;
    private MongoOutboxRepository outboxRepository;
    private IdempotencyService idempotencyService;

    @BeforeEach
    void setUp() throws Exception {
        processedEventRepository = new MongoProcessedEventRepository(processedEventStore, mongoTemplate);
        outboxRepository = new MongoOutboxRepository(outboxEntryStore, mongoTemplate);

        processedEventStore.deleteAll();
        outboxEntryStore.deleteAll();

        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        idempotencyService = new IdempotencyService(
                processedEventRepository,
                outboxRepository,
                objectMapper,
                new SimpleMeterRegistry());
    }

    // -------------------------------------------------------------------------
    // Core idempotency guarantee
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("given a payment event processed for the first time")
    class GivenFirstTimeProcessing {

        @Test
        @DisplayName("when processMessage is called, then returns success and persists a ProcessedEvent")
        void whenProcessMessage_thenReturnsSuccessAndPersistsEvent() {
            // Given
            OutboxPayload payload = OutboxPayload.restPost(
                    "https://ledger.internal/api/v1/transactions", Map.of("amount", 500));

            // When
            IdempotencyService.ProcessingResult result =
                    idempotencyService.processMessage("mongo-txn-001", "MQ_SOURCE", "LEDGER", payload);

            // Then
            assertThat(result.isSuccess())
                    .as("first processing of a new key must succeed")
                    .isTrue();
            assertThat(processedEventRepository.existsByIdempotencyKey("mongo-txn-001"))
                    .as("ProcessedEvent must be persisted in MongoDB")
                    .isTrue();
        }

        @Test
        @DisplayName("when processMessage is called, then ProcessedEvent is saved in PROCESSING state")
        void whenProcessMessage_thenEventSavedInProcessingState() {
            // Given
            OutboxPayload payload = OutboxPayload.restPost(
                    "https://ledger.internal/api/v1/transactions", Map.of("amount", 250));

            // When
            idempotencyService.processMessage("mongo-txn-002", "MQ_SOURCE", "LEDGER", payload);

            // Then
            Optional<ProcessedEvent> saved = processedEventRepository.findByIdempotencyKey("mongo-txn-002");
            assertThat(saved).isPresent();
            assertThat(saved.get().getState())
                    .as("event must be in PROCESSING state after initial receipt")
                    .isEqualTo(PaymentState.PROCESSING);
        }

        @Test
        @DisplayName("when processMessage is called, then an OutboxEntry is also created")
        void whenProcessMessage_thenOutboxEntryIsCreated() {
            // Given
            OutboxPayload payload = OutboxPayload.restPost(
                    "https://ledger.internal/api/v1/transactions", Map.of("amount", 100));

            // When
            IdempotencyService.ProcessingResult result =
                    idempotencyService.processMessage("mongo-txn-003", "MQ_SOURCE", "LEDGER", payload);

            // Then
            assertThat(result.getOutboxEntry())
                    .as("an OutboxEntry must be created alongside the ProcessedEvent")
                    .isNotNull();
            assertThat(outboxRepository.findByIdempotencyKey("mongo-txn-003"))
                    .as("OutboxEntry must be persisted in MongoDB")
                    .hasSize(1);
        }
    }

    @Nested
    @DisplayName("given a payment event submitted twice with the same idempotency key")
    class GivenDuplicateSubmission {

        @Test
        @DisplayName("when processMessage is called a second time, then returns duplicate result")
        void whenProcessMessageCalledTwice_thenSecondReturnsDuplicate() {
            // Given
            OutboxPayload payload = OutboxPayload.restPost(
                    "https://ledger.internal/api/v1/transactions", Map.of("amount", 100));

            // When
            IdempotencyService.ProcessingResult first =
                    idempotencyService.processMessage("mongo-dup-001", "MQ_SOURCE", "LEDGER", payload);
            IdempotencyService.ProcessingResult second =
                    idempotencyService.processMessage("mongo-dup-001", "MQ_SOURCE", "LEDGER", payload);

            // Then
            assertThat(first.isSuccess())
                    .as("first submission must succeed")
                    .isTrue();
            assertThat(second.isDuplicate())
                    .as("second submission with the same key must be detected as duplicate")
                    .isTrue();
        }

        @Test
        @DisplayName("when processMessage is called a second time, then only one OutboxEntry exists")
        void whenProcessMessageCalledTwice_thenOnlyOneOutboxEntry() {
            // Given
            OutboxPayload payload = OutboxPayload.restPost(
                    "https://ledger.internal/api/v1/transactions", Map.of("amount", 100));

            // When
            idempotencyService.processMessage("mongo-dup-002", "MQ_SOURCE", "LEDGER", payload);
            idempotencyService.processMessage("mongo-dup-002", "MQ_SOURCE", "LEDGER", payload);

            // Then
            assertThat(outboxRepository.findByIdempotencyKey("mongo-dup-002"))
                    .as("only one OutboxEntry must be stored regardless of duplicate submissions")
                    .hasSize(1);
        }
    }

    @Nested
    @DisplayName("given two different payment events")
    class GivenTwoDifferentKeys {

        @Test
        @DisplayName("when each is processed, then both succeed and both are persisted independently")
        void whenBothProcessed_thenBothSucceedAndArePersisted() {
            // Given
            OutboxPayload payload = OutboxPayload.restPost(
                    "https://ledger.internal/api/v1/transactions", Map.of("amount", 100));

            // When
            IdempotencyService.ProcessingResult first =
                    idempotencyService.processMessage("mongo-diff-001", "MQ_SOURCE", "LEDGER", payload);
            IdempotencyService.ProcessingResult second =
                    idempotencyService.processMessage("mongo-diff-002", "MQ_SOURCE", "LEDGER", payload);

            // Then
            assertThat(first.isSuccess()).isTrue();
            assertThat(second.isSuccess()).isTrue();
            assertThat(processedEventRepository.existsByIdempotencyKey("mongo-diff-001")).isTrue();
            assertThat(processedEventRepository.existsByIdempotencyKey("mongo-diff-002")).isTrue();
        }
    }

    // -------------------------------------------------------------------------
    // markCompleted lifecycle
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("given a successfully processed event")
    class GivenSuccessfullyProcessedEvent {

        @Test
        @DisplayName("when markCompleted is called, then the event transitions to COMPLETED in MongoDB")
        void whenMarkCompleted_thenEventIsCompletedInMongoDB() {
            // Given
            OutboxPayload payload = OutboxPayload.restPost(
                    "https://ledger.internal/api/v1/transactions", Map.of("amount", 100));
            idempotencyService.processMessage("mongo-cmp-001", "MQ_SOURCE", "LEDGER", payload);

            // When
            boolean marked = idempotencyService.markCompleted("mongo-cmp-001");

            // Then
            assertThat(marked).isTrue();
            Optional<ProcessedEvent> event = processedEventRepository.findByIdempotencyKey("mongo-cmp-001");
            assertThat(event).isPresent();
            assertThat(event.get().getState()).isEqualTo(PaymentState.COMPLETED);
            assertThat(event.get().getCompletedAt())
                    .as("completedAt timestamp must be set")
                    .isNotNull();
        }

        @Test
        @DisplayName("when marked completed and submitted again, then second submission is still a duplicate")
        void whenCompletedAndSubmittedAgain_thenStillDuplicate() {
            // Given
            OutboxPayload payload = OutboxPayload.restPost(
                    "https://ledger.internal/api/v1/transactions", Map.of("amount", 100));
            idempotencyService.processMessage("mongo-cmp-002", "MQ_SOURCE", "LEDGER", payload);
            idempotencyService.markCompleted("mongo-cmp-002");

            // When — simulate redelivery after successful completion
            IdempotencyService.ProcessingResult redelivery =
                    idempotencyService.processMessage("mongo-cmp-002", "MQ_SOURCE", "LEDGER", payload);

            // Then
            assertThat(redelivery.isDuplicate())
                    .as("completed event redelivered must still be treated as duplicate")
                    .isTrue();
        }
    }

    // -------------------------------------------------------------------------
    // recordFailure lifecycle
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("given a delivery failure")
    class GivenDeliveryFailure {

        @Test
        @DisplayName("when recordFailure is called, then event transitions to FAILED state in MongoDB")
        void whenRecordFailure_thenEventIsFailedInMongoDB() {
            // Given
            OutboxPayload payload = OutboxPayload.restPost(
                    "https://ledger.internal/api/v1/transactions", Map.of("amount", 100));
            idempotencyService.processMessage("mongo-fail-001", "MQ_SOURCE", "LEDGER", payload);

            // When
            boolean recorded = idempotencyService.recordFailure("mongo-fail-001", "HTTP 503");

            // Then
            assertThat(recorded).isTrue();
            Optional<ProcessedEvent> event = processedEventRepository.findByIdempotencyKey("mongo-fail-001");
            assertThat(event).isPresent();
            assertThat(event.get().getState()).isEqualTo(PaymentState.FAILED);
            assertThat(event.get().getLastError()).isEqualTo("HTTP 503");
        }
    }
}
