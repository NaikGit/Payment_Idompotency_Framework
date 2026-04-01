package com.dev.payments.framework.mongodb.repository;

import com.dev.payments.framework.model.ProcessedEvent;
import com.dev.payments.framework.state.PaymentState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository adapter test: real MongoProcessedEventRepository against Flapdoodle embedded MongoDB.
 * Verifies all 10 contract methods defined in ProcessedEventRepository.
 */
@DataMongoTest
@ActiveProfiles("test")
@DisplayName("MongoProcessedEventRepository")
class MongoProcessedEventRepositoryTest {

    @Autowired
    private ProcessedEventMongoStore store;

    @Autowired
    private MongoTemplate mongoTemplate;

    private MongoProcessedEventRepository repository;

    @BeforeEach
    void setUp() {
        repository = new MongoProcessedEventRepository(store, mongoTemplate);
        store.deleteAll();
    }

    // -------------------------------------------------------------------------
    // save + findByIdempotencyKey (round-trip)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("given a saved ProcessedEvent")
    class GivenSavedEvent {

        @Test
        @DisplayName("when findByIdempotencyKey is called, then all fields are preserved")
        void whenFindByIdempotencyKey_thenAllFieldsPreserved() {
            // Given
            Instant receivedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            ProcessedEvent event = ProcessedEvent.builder()
                    .idempotencyKey("mongo-rtt-001")
                    .state(PaymentState.PROCESSING)
                    .source("QUEUE_A")
                    .destination("LEDGER")
                    .receivedAt(receivedAt)
                    .processedBy("node-1")
                    .retryCount(0)
                    .build();

            repository.save(event);

            // When
            Optional<ProcessedEvent> found = repository.findByIdempotencyKey("mongo-rtt-001");

            // Then
            assertThat(found).isPresent();
            assertThat(found.get().getIdempotencyKey()).isEqualTo("mongo-rtt-001");
            assertThat(found.get().getState()).isEqualTo(PaymentState.PROCESSING);
            assertThat(found.get().getSource()).isEqualTo("QUEUE_A");
            assertThat(found.get().getDestination()).isEqualTo("LEDGER");
            assertThat(found.get().getProcessedBy()).isEqualTo("node-1");
            assertThat(found.get().getRetryCount()).isZero();
        }

        @Test
        @DisplayName("when existsByIdempotencyKey is called, then returns true")
        void whenExistsByIdempotencyKey_thenReturnsTrue() {
            // Given
            saveWithState("mongo-exists-001", PaymentState.PROCESSING);

            // When / Then
            assertThat(repository.existsByIdempotencyKey("mongo-exists-001")).isTrue();
        }
    }

    @Nested
    @DisplayName("given no event exists for a key")
    class GivenNoEvent {

        @Test
        @DisplayName("when findByIdempotencyKey is called, then returns empty Optional")
        void whenFindByIdempotencyKey_thenReturnsEmpty() {
            assertThat(repository.findByIdempotencyKey("mongo-nonexistent")).isEmpty();
        }

        @Test
        @DisplayName("when existsByIdempotencyKey is called, then returns false")
        void whenExistsByIdempotencyKey_thenReturnsFalse() {
            assertThat(repository.existsByIdempotencyKey("mongo-nonexistent")).isFalse();
        }
    }

    // -------------------------------------------------------------------------
    // updateState
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("given an event in PROCESSING state")
    class GivenProcessingEvent {

        @Test
        @DisplayName("when updateState to FAILED, then state changes and lastError is set")
        void whenUpdateStateToFailed_thenStateChangesAndLastErrorIsSet() {
            // Given
            saveWithState("mongo-upd-001", PaymentState.PROCESSING);

            // When
            boolean updated = repository.updateState("mongo-upd-001", PaymentState.FAILED, "HTTP 503");

            // Then
            assertThat(updated).isTrue();
            ProcessedEvent event = repository.findByIdempotencyKey("mongo-upd-001").get();
            assertThat(event.getState()).isEqualTo(PaymentState.FAILED);
            assertThat(event.getLastError()).isEqualTo("HTTP 503");
        }

        @Test
        @DisplayName("when updateState to SENDING, then state changes with no error")
        void whenUpdateStateToSending_thenStateChanges() {
            // Given
            saveWithState("mongo-upd-002", PaymentState.PROCESSING);

            // When
            boolean updated = repository.updateState("mongo-upd-002", PaymentState.SENDING, null);

            // Then
            assertThat(updated).isTrue();
            assertThat(repository.findByIdempotencyKey("mongo-upd-002").get().getState())
                    .isEqualTo(PaymentState.SENDING);
        }
    }

    @Nested
    @DisplayName("given updateState called with an unknown key")
    class GivenUpdateStateUnknownKey {

        @Test
        @DisplayName("when updateState is called, then returns false")
        void whenUpdateState_thenReturnsFalse() {
            assertThat(repository.updateState("mongo-unknown", PaymentState.FAILED, "error"))
                    .isFalse();
        }
    }

    // -------------------------------------------------------------------------
    // markCompleted
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("given an event in SENDING state")
    class GivenSendingEvent {

        @Test
        @DisplayName("when markCompleted is called, then state is COMPLETED and completedAt is set")
        void whenMarkCompleted_thenStateIsCompletedAndCompletedAtIsSet() {
            // Given
            saveWithState("mongo-cmp-001", PaymentState.SENDING);
            Instant before = Instant.now().truncatedTo(ChronoUnit.MILLIS);

            // When
            boolean result = repository.markCompleted("mongo-cmp-001");

            // Then
            assertThat(result).isTrue();
            ProcessedEvent event = repository.findByIdempotencyKey("mongo-cmp-001").get();
            assertThat(event.getState()).isEqualTo(PaymentState.COMPLETED);
            assertThat(event.getCompletedAt())
                    .as("completedAt should be set on marking completed")
                    .isNotNull()
                    .isAfterOrEqualTo(before);
        }
    }

    @Nested
    @DisplayName("given markCompleted called with an unknown key")
    class GivenMarkCompletedUnknownKey {

        @Test
        @DisplayName("when markCompleted is called, then returns false")
        void whenMarkCompleted_thenReturnsFalse() {
            assertThat(repository.markCompleted("mongo-unknown")).isFalse();
        }
    }

    // -------------------------------------------------------------------------
    // findByState
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("given events in mixed states")
    class GivenMixedStateEvents {

        @Test
        @DisplayName("when findByState PROCESSING, then returns only PROCESSING events")
        void whenFindByStateProcessing_thenReturnsOnlyProcessingEvents() {
            // Given
            saveWithState("mongo-state-proc-001", PaymentState.PROCESSING);
            saveWithState("mongo-state-proc-002", PaymentState.PROCESSING);
            saveWithState("mongo-state-fail-001", PaymentState.FAILED);

            // When
            List<ProcessedEvent> results = repository.findByState(PaymentState.PROCESSING);

            // Then
            assertThat(results)
                    .extracting(ProcessedEvent::getIdempotencyKey)
                    .contains("mongo-state-proc-001", "mongo-state-proc-002")
                    .doesNotContain("mongo-state-fail-001");
        }

        @Test
        @DisplayName("when countByState FAILED, then returns correct count")
        void whenCountByStateFailed_thenReturnsCorrectCount() {
            // Given
            saveWithState("mongo-cnt-fail-001", PaymentState.FAILED);
            saveWithState("mongo-cnt-fail-002", PaymentState.FAILED);
            saveWithState("mongo-cnt-proc-001", PaymentState.PROCESSING);

            // When
            long count = repository.countByState(PaymentState.FAILED);

            // Then
            assertThat(count).isGreaterThanOrEqualTo(2);
        }
    }

    // -------------------------------------------------------------------------
    // findStuckProcessing
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("given an event stuck in PROCESSING state for over an hour")
    class GivenStuckProcessingEvent {

        @Test
        @DisplayName("when findStuckProcessing is called with a 1-hour threshold, then returns the stuck event")
        void whenFindStuckProcessing_thenReturnsStuckEvent() {
            // Given — event received 2 hours ago, still PROCESSING
            ProcessedEvent stuckEvent = ProcessedEvent.builder()
                    .idempotencyKey("mongo-stuck-001")
                    .state(PaymentState.PROCESSING)
                    .source("QUEUE_A")
                    .destination("LEDGER")
                    .receivedAt(Instant.now().minus(2, ChronoUnit.HOURS))
                    .build();
            repository.save(stuckEvent);

            ProcessedEvent recentEvent = ProcessedEvent.builder()
                    .idempotencyKey("mongo-recent-proc-001")
                    .state(PaymentState.PROCESSING)
                    .source("QUEUE_A")
                    .destination("LEDGER")
                    .receivedAt(Instant.now().minusSeconds(30))
                    .build();
            repository.save(recentEvent);

            // When
            Instant oneHourAgo = Instant.now().minus(1, ChronoUnit.HOURS);
            List<ProcessedEvent> stuck = repository.findStuckProcessing(oneHourAgo);

            // Then
            assertThat(stuck)
                    .extracting(ProcessedEvent::getIdempotencyKey)
                    .contains("mongo-stuck-001")
                    .doesNotContain("mongo-recent-proc-001");
        }
    }

    // -------------------------------------------------------------------------
    // findRecentFailures
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("given multiple FAILED events")
    class GivenMultipleFailedEvents {

        @Test
        @DisplayName("when findRecentFailures with limit 2, then returns at most 2 FAILED events")
        void whenFindRecentFailures_thenRespectsLimit() {
            // Given
            saveWithState("mongo-rf-001", PaymentState.FAILED);
            saveWithState("mongo-rf-002", PaymentState.FAILED);
            saveWithState("mongo-rf-003", PaymentState.FAILED);

            // When
            List<ProcessedEvent> failures = repository.findRecentFailures(2);

            // Then
            assertThat(failures).hasSizeLessThanOrEqualTo(2);
            assertThat(failures).allMatch(e -> e.getState() == PaymentState.FAILED);
        }
    }

    // -------------------------------------------------------------------------
    // deleteCompletedBefore
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("given old COMPLETED events and recent COMPLETED events")
    class GivenMixedAgeCompletedEvents {

        @Test
        @DisplayName("when deleteCompletedBefore is called, then removes only old COMPLETED events")
        void whenDeleteCompletedBefore_thenRemovesOnlyOldEvents() {
            // Given
            Instant thirtyDaysAgo = Instant.now().minus(30, ChronoUnit.DAYS);

            // Old completed event — set completedAt directly via updateState path + markCompleted trick
            saveWithState("mongo-old-cmp-001", PaymentState.SENDING);
            repository.markCompleted("mongo-old-cmp-001");
            // Force completedAt to 31 days ago so it falls before the threshold
            mongoTemplate.updateFirst(
                    org.springframework.data.mongodb.core.query.Query.query(
                            org.springframework.data.mongodb.core.query.Criteria.where("_id").is("mongo-old-cmp-001")),
                    new org.springframework.data.mongodb.core.query.Update()
                            .set("completed_at", thirtyDaysAgo.minus(1, ChronoUnit.HOURS)),
                    com.dev.payments.framework.mongodb.document.ProcessedEventDocument.class);

            // Recent completed event
            saveWithState("mongo-recent-cmp-001", PaymentState.SENDING);
            repository.markCompleted("mongo-recent-cmp-001");

            // When
            int deleted = repository.deleteCompletedBefore(thirtyDaysAgo);

            // Then
            assertThat(deleted).isGreaterThanOrEqualTo(1);
            assertThat(repository.existsByIdempotencyKey("mongo-old-cmp-001"))
                    .as("old completed event should be deleted")
                    .isFalse();
            assertThat(repository.existsByIdempotencyKey("mongo-recent-cmp-001"))
                    .as("recent completed event should be retained")
                    .isTrue();
        }

        @Test
        @DisplayName("when deleteCompletedBefore is called, then does NOT delete FAILED or PROCESSING events")
        void whenDeleteCompletedBefore_thenDoesNotDeleteNonCompletedEvents() {
            // Given
            saveWithState("mongo-failed-keep-001", PaymentState.FAILED);

            // When
            repository.deleteCompletedBefore(Instant.now());

            // Then — FAILED event must be retained
            assertThat(repository.existsByIdempotencyKey("mongo-failed-keep-001")).isTrue();
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void saveWithState(String key, PaymentState state) {
        repository.save(ProcessedEvent.builder()
                .idempotencyKey(key)
                .state(state)
                .source("QUEUE_A")
                .destination("LEDGER")
                .receivedAt(Instant.now())
                .build());
    }
}
