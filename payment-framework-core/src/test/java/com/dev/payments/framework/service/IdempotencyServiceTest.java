package com.dev.payments.framework.service;

import com.dev.payments.framework.model.OutboxEntry;
import com.dev.payments.framework.model.OutboxPayload;
import com.dev.payments.framework.model.ProcessedEvent;
import com.dev.payments.framework.repository.OutboxRepository;
import com.dev.payments.framework.repository.ProcessedEventRepository;
import com.dev.payments.framework.state.PaymentState;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("IdempotencyService")
class IdempotencyServiceTest {

    @Mock private ProcessedEventRepository processedEventRepository;
    @Mock private OutboxRepository outboxRepository;
    @Mock private ObjectMapper objectMapper;
    @Mock private MeterRegistry meterRegistry;

    private IdempotencyService service;

    @BeforeEach
    void setUp() {
        // MeterRegistry builder chains must return non-null stubs
        when(meterRegistry.counter(anyString(), any(String[].class))).thenReturn(mock(Counter.class));
        when(meterRegistry.timer(anyString(), any(String[].class))).thenReturn(mock(Timer.class));

        service = new IdempotencyService(
            processedEventRepository, outboxRepository, objectMapper, meterRegistry);
    }

    // -------------------------------------------------------------------------
    // processMessage — duplicate key
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("given a key that has already been processed to COMPLETED")
    class GivenCompletedKey {

        @Test
        @DisplayName("when processMessage is called, then returns a duplicate result")
        void whenProcessMessage_thenReturnsDuplicateResult() {
            // Given
            String key = "txn-dup-001";
            when(processedEventRepository.findByIdempotencyKey(key))
                .thenReturn(Optional.of(completedEvent(key)));

            // When
            IdempotencyService.ProcessingResult result =
                service.processMessage(key, "SOURCE_Q", "LEDGER", restPayload());

            // Then
            assertThat(result.isDuplicate())
                .as("result must be a duplicate when key already exists")
                .isTrue();
            assertThat(result.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("when processMessage is called, then no OutboxEntry is created")
        void whenProcessMessage_thenNoOutboxEntryCreated() {
            // Given
            String key = "txn-dup-002";
            when(processedEventRepository.findByIdempotencyKey(key))
                .thenReturn(Optional.of(completedEvent(key)));

            // When
            service.processMessage(key, "SOURCE_Q", "LEDGER", restPayload());

            // Then
            verify(outboxRepository, never()).save(any());
        }

        @Test
        @DisplayName("when processMessage is called, then the existing event is returned in the result")
        void whenProcessMessage_thenExistingEventReturnedInResult() {
            // Given
            String key = "txn-dup-003";
            ProcessedEvent existing = completedEvent(key);
            when(processedEventRepository.findByIdempotencyKey(key))
                .thenReturn(Optional.of(existing));

            // When
            IdempotencyService.ProcessingResult result =
                service.processMessage(key, "SOURCE_Q", "LEDGER", restPayload());

            // Then
            assertThat(result.getEvent().getIdempotencyKey()).isEqualTo(key);
            assertThat(result.getEvent().getState()).isEqualTo(PaymentState.COMPLETED);
        }
    }

    @Nested
    @DisplayName("given a key in PROCESSING state (in-flight)")
    class GivenProcessingKey {

        @Test
        @DisplayName("when processMessage is called, then returns a duplicate result without creating a new entry")
        void whenProcessMessage_thenReturnsDuplicateWithoutNewEntry() {
            // Given
            String key = "txn-inflight-001";
            when(processedEventRepository.findByIdempotencyKey(key))
                .thenReturn(Optional.of(eventWithState(key, PaymentState.PROCESSING)));

            // When
            IdempotencyService.ProcessingResult result =
                service.processMessage(key, "SOURCE_Q", "LEDGER", restPayload());

            // Then
            assertThat(result.isDuplicate()).isTrue();
            verify(outboxRepository, never()).save(any());
        }
    }

    // -------------------------------------------------------------------------
    // processMessage — new key
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("given a new, unseen idempotency key")
    class GivenNewKey {

        @BeforeEach
        void setUp() throws Exception {
            when(objectMapper.writeValueAsString(any())).thenReturn("{\"amount\":100}");
            when(processedEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(outboxRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        }

        @Test
        @DisplayName("when processMessage is called, then returns a success result")
        void whenProcessMessage_thenReturnsSuccessResult() {
            // Given
            when(processedEventRepository.findByIdempotencyKey("txn-new-001"))
                .thenReturn(Optional.empty());

            // When
            IdempotencyService.ProcessingResult result =
                service.processMessage("txn-new-001", "SOURCE_Q", "LEDGER", restPayload());

            // Then
            assertThat(result.isSuccess())
                .as("result must be success for a new key")
                .isTrue();
            assertThat(result.isDuplicate()).isFalse();
        }

        @Test
        @DisplayName("when processMessage is called, then saves ProcessedEvent in PROCESSING state")
        void whenProcessMessage_thenSavesProcessedEventInProcessingState() {
            // Given
            when(processedEventRepository.findByIdempotencyKey("txn-new-002"))
                .thenReturn(Optional.empty());

            // When
            service.processMessage("txn-new-002", "SOURCE_Q", "LEDGER", restPayload());

            // Then
            verify(processedEventRepository).save(argThat(event ->
                event.getIdempotencyKey().equals("txn-new-002")
                && event.getState() == PaymentState.PROCESSING
                && "SOURCE_Q".equals(event.getSource())
                && "LEDGER".equals(event.getDestination())
            ));
        }

        @Test
        @DisplayName("when processMessage is called, then saves an OutboxEntry linked to the idempotency key")
        void whenProcessMessage_thenSavesOutboxEntryLinkedToKey() {
            // Given
            when(processedEventRepository.findByIdempotencyKey("txn-new-003"))
                .thenReturn(Optional.empty());

            // When
            service.processMessage("txn-new-003", "SOURCE_Q", "LEDGER", restPayload());

            // Then
            verify(outboxRepository).save(argThat(entry ->
                "txn-new-003".equals(entry.getIdempotencyKey())
                && "LEDGER".equals(entry.getDestination())
                && entry.getStatus() == OutboxEntry.OutboxStatus.PENDING
            ));
        }

        @Test
        @DisplayName("when processMessage is called, then the result contains both the event and the outbox entry")
        void whenProcessMessage_thenResultContainsBothEventAndOutboxEntry() {
            // Given
            when(processedEventRepository.findByIdempotencyKey("txn-new-004"))
                .thenReturn(Optional.empty());

            // When
            IdempotencyService.ProcessingResult result =
                service.processMessage("txn-new-004", "SOURCE_Q", "LEDGER", restPayload());

            // Then
            assertThat(result.getEvent()).isNotNull();
            assertThat(result.getOutboxEntry()).isNotNull();
        }
    }

    // -------------------------------------------------------------------------
    // isDuplicate / checkDuplicate
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("given isDuplicate checks")
    class GivenIsDuplicateChecks {

        @Test
        @DisplayName("given an existing key, when isDuplicate is called, then returns true")
        void givenExistingKey_whenIsDuplicate_thenReturnsTrue() {
            when(processedEventRepository.findByIdempotencyKey("txn-exists"))
                .thenReturn(Optional.of(completedEvent("txn-exists")));

            assertThat(service.isDuplicate("txn-exists")).isTrue();
        }

        @Test
        @DisplayName("given an unknown key, when isDuplicate is called, then returns false")
        void givenUnknownKey_whenIsDuplicate_thenReturnsFalse() {
            when(processedEventRepository.findByIdempotencyKey("txn-unknown"))
                .thenReturn(Optional.empty());

            assertThat(service.isDuplicate("txn-unknown")).isFalse();
        }

        @Test
        @DisplayName("given an existing key, when checkDuplicate is called, then returns the existing event")
        void givenExistingKey_whenCheckDuplicate_thenReturnsEvent() {
            ProcessedEvent existing = completedEvent("txn-check-001");
            when(processedEventRepository.findByIdempotencyKey("txn-check-001"))
                .thenReturn(Optional.of(existing));

            Optional<ProcessedEvent> result = service.checkDuplicate("txn-check-001");

            assertThat(result).isPresent();
            assertThat(result.get().getIdempotencyKey()).isEqualTo("txn-check-001");
        }
    }

    // -------------------------------------------------------------------------
    // markCompleted
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("given markCompleted")
    class GivenMarkCompleted {

        @Test
        @DisplayName("given an existing key, when markCompleted is called, then delegates to repository and returns true")
        void givenExistingKey_whenMarkCompleted_thenReturnsTrueAndDelegates() {
            when(processedEventRepository.markCompleted("txn-cmp-001")).thenReturn(true);

            boolean result = service.markCompleted("txn-cmp-001");

            assertThat(result).isTrue();
            verify(processedEventRepository).markCompleted("txn-cmp-001");
        }

        @Test
        @DisplayName("given an unknown key, when markCompleted is called, then returns false")
        void givenUnknownKey_whenMarkCompleted_thenReturnsFalse() {
            when(processedEventRepository.markCompleted("txn-unknown")).thenReturn(false);

            assertThat(service.markCompleted("txn-unknown")).isFalse();
        }
    }

    // -------------------------------------------------------------------------
    // recordFailure
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("given recordFailure")
    class GivenRecordFailure {

        @Test
        @DisplayName("given an existing key, when recordFailure is called, then delegates to repository with FAILED state")
        void givenExistingKey_whenRecordFailure_thenDelegatesToRepository() {
            when(processedEventRepository.updateState(
                eq("txn-fail-001"), eq(PaymentState.FAILED), anyString()))
                .thenReturn(true);

            boolean result = service.recordFailure("txn-fail-001", "HTTP 503");

            assertThat(result).isTrue();
            verify(processedEventRepository).updateState(
                eq("txn-fail-001"), eq(PaymentState.FAILED), eq("HTTP 503"));
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ProcessedEvent completedEvent(String key) {
        return ProcessedEvent.builder()
            .idempotencyKey(key)
            .state(PaymentState.COMPLETED)
            .source("SOURCE_Q")
            .destination("LEDGER")
            .receivedAt(Instant.now().minusSeconds(60))
            .completedAt(Instant.now().minusSeconds(30))
            .build();
    }

    private ProcessedEvent eventWithState(String key, PaymentState state) {
        return ProcessedEvent.builder()
            .idempotencyKey(key)
            .state(state)
            .source("SOURCE_Q")
            .destination("LEDGER")
            .receivedAt(Instant.now().minusSeconds(10))
            .build();
    }

    private OutboxPayload restPayload() {
        return OutboxPayload.restPost("https://ledger.internal/api/v1/transactions",
            java.util.Map.of("amount", 100));
    }
}
