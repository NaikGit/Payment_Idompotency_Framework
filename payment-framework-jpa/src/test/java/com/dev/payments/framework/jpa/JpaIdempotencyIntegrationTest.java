package com.dev.payments.framework.jpa;

import com.dev.payments.framework.jpa.repository.JpaOutboxRepository;
import com.dev.payments.framework.jpa.repository.JpaProcessedEventRepository;
import com.dev.payments.framework.jpa.repository.OutboxEntryJpaRepository;
import com.dev.payments.framework.jpa.repository.ProcessedEventJpaRepository;
import com.dev.payments.framework.model.OutboxPayload;
import com.dev.payments.framework.model.ProcessedEvent;
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
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test: real JpaProcessedEventRepository + real JpaOutboxRepository
 * against H2 + real IdempotencyService.
 *
 * Both repositories are fully wired — no mocks. This verifies the idempotency
 * guarantee end-to-end through the JPA persistence layer, including the atomic
 * co-creation of ProcessedEvent + OutboxEntry.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@ActiveProfiles("test")
@DisplayName("Idempotency integration (JPA + H2)")
class JpaIdempotencyIntegrationTest {

    @Autowired
    private ProcessedEventJpaRepository processedEventJpaRepository;

    @Autowired
    private OutboxEntryJpaRepository outboxEntryJpaRepository;

    private JpaProcessedEventRepository processedEventRepository;
    private JpaOutboxRepository outboxRepository;
    private IdempotencyService idempotencyService;

    @BeforeEach
    void setUp() throws Exception {
        processedEventRepository = new JpaProcessedEventRepository(processedEventJpaRepository);
        outboxRepository = new JpaOutboxRepository(outboxEntryJpaRepository);

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
                    idempotencyService.processMessage("txn-int-001", "MQ_SOURCE", "CENTRAL_LEDGER", payload);

            // Then
            assertThat(result.isSuccess())
                    .as("first processing of a new key must succeed")
                    .isTrue();
            assertThat(processedEventRepository.existsByIdempotencyKey("txn-int-001"))
                    .as("ProcessedEvent must be persisted in the database")
                    .isTrue();
        }

        @Test
        @DisplayName("when processMessage is called, then ProcessedEvent is saved in PROCESSING state")
        void whenProcessMessage_thenEventSavedInProcessingState() {
            // Given
            OutboxPayload payload = OutboxPayload.restPost(
                    "https://ledger.internal/api/v1/transactions", Map.of("amount", 250));

            // When
            idempotencyService.processMessage("txn-int-002", "MQ_SOURCE", "CENTRAL_LEDGER", payload);

            // Then
            Optional<ProcessedEvent> saved =
                    processedEventRepository.findByIdempotencyKey("txn-int-002");
            assertThat(saved).isPresent();
            assertThat(saved.get().getState())
                    .as("event must be in PROCESSING state after initial receipt")
                    .isEqualTo(PaymentState.PROCESSING);
        }

        @Test
        @DisplayName("when processMessage is called, then an OutboxEntry is also persisted")
        void whenProcessMessage_thenOutboxEntryIsPersisted() {
            // Given
            OutboxPayload payload = OutboxPayload.restPost(
                    "https://ledger.internal/api/v1/transactions", Map.of("amount", 100));

            // When
            IdempotencyService.ProcessingResult result =
                    idempotencyService.processMessage("txn-int-003", "MQ_SOURCE", "CENTRAL_LEDGER", payload);

            // Then
            assertThat(result.getOutboxEntry())
                    .as("an OutboxEntry must be returned in the result")
                    .isNotNull();
            assertThat(outboxRepository.findByIdempotencyKey("txn-int-003"))
                    .as("OutboxEntry must be persisted in the database")
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
                    idempotencyService.processMessage("txn-dup-int-001", "MQ_SOURCE", "LEDGER", payload);
            IdempotencyService.ProcessingResult second =
                    idempotencyService.processMessage("txn-dup-int-001", "MQ_SOURCE", "LEDGER", payload);

            // Then
            assertThat(first.isSuccess()).as("first submission must succeed").isTrue();
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
            idempotencyService.processMessage("txn-dup-int-002", "MQ_SOURCE", "LEDGER", payload);
            idempotencyService.processMessage("txn-dup-int-002", "MQ_SOURCE", "LEDGER", payload);

            // Then
            assertThat(outboxRepository.findByIdempotencyKey("txn-dup-int-002"))
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
                    idempotencyService.processMessage("txn-diff-001", "MQ_SOURCE", "LEDGER", payload);
            IdempotencyService.ProcessingResult second =
                    idempotencyService.processMessage("txn-diff-002", "MQ_SOURCE", "LEDGER", payload);

            // Then
            assertThat(first.isSuccess()).isTrue();
            assertThat(second.isSuccess()).isTrue();
            assertThat(processedEventRepository.existsByIdempotencyKey("txn-diff-001")).isTrue();
            assertThat(processedEventRepository.existsByIdempotencyKey("txn-diff-002")).isTrue();
        }
    }

    // -------------------------------------------------------------------------
    // markCompleted lifecycle
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("given a successfully processed event")
    class GivenSuccessfullyProcessedEvent {

        @Test
        @DisplayName("when markCompleted is called, then the event transitions to COMPLETED in the database")
        void whenMarkCompleted_thenEventIsCompletedInDatabase() {
            // Given
            OutboxPayload payload = OutboxPayload.restPost(
                    "https://ledger.internal/api/v1/transactions", Map.of("amount", 100));
            idempotencyService.processMessage("txn-complete-001", "MQ_SOURCE", "LEDGER", payload);

            // When
            boolean marked = idempotencyService.markCompleted("txn-complete-001");

            // Then
            assertThat(marked).isTrue();
            Optional<ProcessedEvent> event =
                    processedEventRepository.findByIdempotencyKey("txn-complete-001");
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
            idempotencyService.processMessage("txn-complete-002", "MQ_SOURCE", "LEDGER", payload);
            idempotencyService.markCompleted("txn-complete-002");

            // When — simulate redelivery after successful completion
            IdempotencyService.ProcessingResult redelivery =
                    idempotencyService.processMessage("txn-complete-002", "MQ_SOURCE", "LEDGER", payload);

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
        @DisplayName("when recordFailure is called, then event transitions to FAILED state")
        void whenRecordFailure_thenEventIsFailedInDatabase() {
            // Given
            OutboxPayload payload = OutboxPayload.restPost(
                    "https://ledger.internal/api/v1/transactions", Map.of("amount", 100));
            idempotencyService.processMessage("txn-fail-int-001", "MQ_SOURCE", "LEDGER", payload);

            // When
            boolean recorded = idempotencyService.recordFailure("txn-fail-int-001", "HTTP 503");

            // Then
            assertThat(recorded).isTrue();
            Optional<ProcessedEvent> event =
                    processedEventRepository.findByIdempotencyKey("txn-fail-int-001");
            assertThat(event).isPresent();
            assertThat(event.get().getState()).isEqualTo(PaymentState.FAILED);
            assertThat(event.get().getLastError()).isEqualTo("HTTP 503");
        }
    }
}
