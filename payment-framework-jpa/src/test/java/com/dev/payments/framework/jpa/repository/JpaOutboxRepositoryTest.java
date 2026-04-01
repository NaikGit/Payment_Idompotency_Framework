package com.dev.payments.framework.jpa.repository;

import com.dev.payments.framework.model.OutboxEntry;
import com.dev.payments.framework.model.OutboxEntry.OutboxStatus;
import com.dev.payments.framework.model.ProcessedEvent;
import com.dev.payments.framework.state.PaymentState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository adapter test: real JpaOutboxRepository against H2 + real schema.sql.
 * Verifies all 14 contract methods defined in OutboxRepository.
 *
 * ProcessedEvent rows are inserted first because outbox_entries has a FK constraint
 * to processed_events(idempotency_key) in schema.sql.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@ActiveProfiles("test")
@DisplayName("JpaOutboxRepository")
class JpaOutboxRepositoryTest {

    @Autowired
    private OutboxEntryJpaRepository outboxJpaRepository;

    @Autowired
    private ProcessedEventJpaRepository processedEventJpaRepository;

    private JpaOutboxRepository repository;
    private JpaProcessedEventRepository processedEventRepository;

    @BeforeEach
    void setUp() {
        repository = new JpaOutboxRepository(outboxJpaRepository);
        processedEventRepository = new JpaProcessedEventRepository(processedEventJpaRepository);
    }

    // -------------------------------------------------------------------------
    // save + findById (round-trip)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("given a saved OutboxEntry")
    class GivenSavedEntry {

        @Test
        @DisplayName("when findById is called, then all fields are preserved")
        void whenFindById_thenAllFieldsPreserved() {
            // Given
            seedProcessedEvent("txn-rtt-001");
            OutboxEntry entry = pendingEntry("outbox-rtt-001", "txn-rtt-001", "LEDGER");
            repository.save(entry);

            // When
            Optional<OutboxEntry> found = repository.findById("outbox-rtt-001");

            // Then
            assertThat(found).isPresent();
            assertThat(found.get().getId()).isEqualTo("outbox-rtt-001");
            assertThat(found.get().getIdempotencyKey()).isEqualTo("txn-rtt-001");
            assertThat(found.get().getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(found.get().getDestination()).isEqualTo("LEDGER");
            assertThat(found.get().getPayload()).isEqualTo("{\"amount\":100}");
        }

        @Test
        @DisplayName("when findByIdempotencyKey is called, then returns all matching entries")
        void whenFindByIdempotencyKey_thenReturnsMatchingEntries() {
            // Given
            seedProcessedEvent("txn-fk-001");
            repository.save(pendingEntry("outbox-fk-001", "txn-fk-001", "LEDGER"));
            repository.save(pendingEntry("outbox-fk-002", "txn-fk-001", "AUDIT"));

            seedProcessedEvent("txn-fk-002");
            repository.save(pendingEntry("outbox-fk-003", "txn-fk-002", "LEDGER"));

            // When
            List<OutboxEntry> results = repository.findByIdempotencyKey("txn-fk-001");

            // Then
            assertThat(results).hasSize(2);
            assertThat(results).extracting(OutboxEntry::getId)
                    .containsExactlyInAnyOrder("outbox-fk-001", "outbox-fk-002");
        }
    }

    // -------------------------------------------------------------------------
    // findReadyForProcessing
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("given a mix of PENDING, RETRY_PENDING, and DELIVERED entries")
    class GivenMixedStatusEntries {

        @Test
        @DisplayName("when findReadyForProcessing, then returns PENDING and past-due RETRY_PENDING only")
        void whenFindReadyForProcessing_thenReturnsPendingAndPastDueRetry() {
            // Given
            seedProcessedEvent("txn-rp-001");
            repository.save(pendingEntry("outbox-rp-001", "txn-rp-001", "LEDGER"));

            seedProcessedEvent("txn-rp-002");
            OutboxEntry futureRetry = OutboxEntry.builder()
                    .id("outbox-rp-002").idempotencyKey("txn-rp-002")
                    .destination("LEDGER").payload("{}").status(OutboxStatus.RETRY_PENDING).build();
            futureRetry.setNextRetryAt(Instant.now().plus(1, ChronoUnit.HOURS));
            repository.save(futureRetry);

            seedProcessedEvent("txn-rp-003");
            OutboxEntry pastRetry = OutboxEntry.builder()
                    .id("outbox-rp-003").idempotencyKey("txn-rp-003")
                    .destination("LEDGER").payload("{}").status(OutboxStatus.RETRY_PENDING).build();
            pastRetry.setNextRetryAt(Instant.now().minus(1, ChronoUnit.HOURS));
            repository.save(pastRetry);

            seedProcessedEvent("txn-rp-004");
            OutboxEntry delivered = OutboxEntry.builder()
                    .id("outbox-rp-004").idempotencyKey("txn-rp-004")
                    .destination("LEDGER").payload("{}").status(OutboxStatus.DELIVERED).build();
            repository.save(delivered);

            // When
            List<OutboxEntry> ready = repository.findReadyForProcessing(10);

            // Then
            assertThat(ready).extracting(OutboxEntry::getId)
                    .contains("outbox-rp-001", "outbox-rp-003")
                    .doesNotContain("outbox-rp-002", "outbox-rp-004");
        }

        @Test
        @DisplayName("when findReadyForProcessing with limit 1, then returns at most 1 entry")
        void whenFindReadyForProcessing_thenRespectsLimit() {
            // Given
            seedProcessedEvent("txn-lim-001");
            repository.save(pendingEntry("outbox-lim-001", "txn-lim-001", "LEDGER"));
            seedProcessedEvent("txn-lim-002");
            repository.save(pendingEntry("outbox-lim-002", "txn-lim-002", "LEDGER"));

            // When
            List<OutboxEntry> ready = repository.findReadyForProcessing(1);

            // Then
            assertThat(ready).hasSize(1);
        }
    }

    // -------------------------------------------------------------------------
    // tryLock — atomicity
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("given an unlocked outbox entry")
    class GivenUnlockedEntry {

        @Test
        @DisplayName("when tryLock is called, then lock is acquired and returns true")
        void whenTryLock_thenLockAcquired() {
            // Given
            seedProcessedEvent("txn-lock-001");
            repository.save(pendingEntry("outbox-lock-001", "txn-lock-001", "LEDGER"));

            // When
            boolean locked = repository.tryLock("outbox-lock-001", "node-1", 30);

            // Then
            assertThat(locked).as("tryLock must return true when entry was unlocked").isTrue();
        }

        @Test
        @DisplayName("when tryLock is called twice by different nodes, then only the first succeeds")
        void whenTryLockCalledTwice_thenOnlyFirstSucceeds() {
            // Given
            seedProcessedEvent("txn-lock-002");
            repository.save(pendingEntry("outbox-lock-002", "txn-lock-002", "LEDGER"));

            // When
            boolean firstLock = repository.tryLock("outbox-lock-002", "node-1", 30);
            boolean secondLock = repository.tryLock("outbox-lock-002", "node-2", 30);

            // Then
            assertThat(firstLock).isTrue();
            assertThat(secondLock)
                    .as("second tryLock must fail because node-1 already holds the lock")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("given an entry with an expired lock")
    class GivenExpiredLock {

        @Test
        @DisplayName("when tryLock is called after expiry, then new lock is acquired")
        void whenTryLockAfterExpiry_thenNewLockAcquired() {
            // Given — save with an already-expired lock via direct JPA entity manipulation
            seedProcessedEvent("txn-exp-001");
            com.dev.payments.framework.jpa.entity.OutboxEntryEntity entity =
                    new com.dev.payments.framework.jpa.entity.OutboxEntryEntity();
            entity.setId("outbox-exp-001");
            entity.setIdempotencyKey("txn-exp-001");
            entity.setStatus(OutboxStatus.PROCESSING);
            entity.setDestination("LEDGER");
            entity.setPayload("{}");
            entity.setCreatedAt(Instant.now().minus(10, ChronoUnit.MINUTES));
            entity.setLockedBy("dead-node");
            entity.setLockExpiresAt(Instant.now().minus(1, ChronoUnit.MINUTES));
            outboxJpaRepository.save(entity);

            // When
            boolean locked = repository.tryLock("outbox-exp-001", "node-2", 30);

            // Then
            assertThat(locked)
                    .as("tryLock must succeed when the previous lock has expired")
                    .isTrue();
        }
    }

    // -------------------------------------------------------------------------
    // releaseLock — ownership validation
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("given a locked outbox entry")
    class GivenLockedEntry {

        @Test
        @DisplayName("when releaseLock is called by the lock owner, then lock is released")
        void whenReleaseLock_byOwner_thenLockReleased() {
            // Given
            seedProcessedEvent("txn-rel-001");
            repository.save(pendingEntry("outbox-rel-001", "txn-rel-001", "LEDGER"));
            repository.tryLock("outbox-rel-001", "node-1", 30);

            // When
            boolean released = repository.releaseLock("outbox-rel-001", "node-1");

            // Then
            assertThat(released).isTrue();
        }

        @Test
        @DisplayName("when releaseLock is called by a different node, then lock is NOT released")
        void whenReleaseLock_byWrongOwner_thenLockNotReleased() {
            // Given
            seedProcessedEvent("txn-rel-002");
            repository.save(pendingEntry("outbox-rel-002", "txn-rel-002", "LEDGER"));
            repository.tryLock("outbox-rel-002", "node-1", 30);

            // When — node-2 tries to steal node-1's lock
            boolean released = repository.releaseLock("outbox-rel-002", "node-2");

            // Then
            assertThat(released)
                    .as("releaseLock must fail when caller does not own the lock")
                    .isFalse();
        }
    }

    // -------------------------------------------------------------------------
    // findExpiredLocks
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("given entries with mixed lock states")
    class GivenMixedLockStates {

        @Test
        @DisplayName("when findExpiredLocks is called, then returns only entries with expired locks")
        void whenFindExpiredLocks_thenReturnsExpiredOnly() {
            // Given — entry with expired lock
            seedProcessedEvent("txn-el-001");
            com.dev.payments.framework.jpa.entity.OutboxEntryEntity expiredEntity =
                    new com.dev.payments.framework.jpa.entity.OutboxEntryEntity();
            expiredEntity.setId("outbox-el-001");
            expiredEntity.setIdempotencyKey("txn-el-001");
            expiredEntity.setStatus(OutboxStatus.PROCESSING);
            expiredEntity.setDestination("LEDGER");
            expiredEntity.setPayload("{}");
            expiredEntity.setCreatedAt(Instant.now());
            expiredEntity.setLockedBy("dead-node");
            expiredEntity.setLockExpiresAt(Instant.now().minus(5, ChronoUnit.MINUTES));
            outboxJpaRepository.save(expiredEntity);

            // Entry with active lock
            seedProcessedEvent("txn-el-002");
            repository.save(pendingEntry("outbox-el-002", "txn-el-002", "LEDGER"));
            repository.tryLock("outbox-el-002", "active-node", 300);

            // Unlocked entry
            seedProcessedEvent("txn-el-003");
            repository.save(pendingEntry("outbox-el-003", "txn-el-003", "LEDGER"));

            // When
            List<OutboxEntry> expired = repository.findExpiredLocks();

            // Then
            assertThat(expired).extracting(OutboxEntry::getId)
                    .contains("outbox-el-001")
                    .doesNotContain("outbox-el-002", "outbox-el-003");
        }
    }

    // -------------------------------------------------------------------------
    // markDelivered
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("given a locked entry being processed")
    class GivenProcessingEntry {

        @Test
        @DisplayName("when markDelivered is called, then status is DELIVERED and deliveredAt is set")
        void whenMarkDelivered_thenStatusIsDeliveredAndDeliveredAtIsSet() {
            // Given
            seedProcessedEvent("txn-del-001");
            repository.save(pendingEntry("outbox-del-001", "txn-del-001", "LEDGER"));
            repository.tryLock("outbox-del-001", "node-1", 30);
            Instant before = Instant.now().truncatedTo(ChronoUnit.MILLIS);

            // When
            boolean marked = repository.markDelivered("outbox-del-001");

            // Then
            assertThat(marked).isTrue();
            OutboxEntry entry = repository.findById("outbox-del-001").get();
            assertThat(entry.getStatus()).isEqualTo(OutboxStatus.DELIVERED);
            assertThat(entry.getDeliveredAt())
                    .as("deliveredAt must be set")
                    .isNotNull()
                    .isAfterOrEqualTo(before);
        }
    }

    // -------------------------------------------------------------------------
    // recordFailure
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("given a delivery failure")
    class GivenDeliveryFailure {

        @Test
        @DisplayName("when recordFailure with a future nextRetryAt, then status is RETRY_PENDING")
        void whenRecordFailure_withRetry_thenStatusIsRetryPending() {
            // Given
            seedProcessedEvent("txn-fail-001");
            repository.save(pendingEntry("outbox-fail-001", "txn-fail-001", "LEDGER"));
            Instant nextRetry = Instant.now().plus(30, ChronoUnit.SECONDS);

            // When
            boolean recorded = repository.recordFailure("outbox-fail-001", "HTTP 503", 503, nextRetry);

            // Then
            assertThat(recorded).isTrue();
            OutboxEntry entry = repository.findById("outbox-fail-001").get();
            assertThat(entry.getStatus()).isEqualTo(OutboxStatus.RETRY_PENDING);
            assertThat(entry.getLastError()).isEqualTo("HTTP 503");
            assertThat(entry.getLastHttpStatus()).isEqualTo(503);
        }

        @Test
        @DisplayName("when recordFailure with null nextRetryAt (max retries), then status is FAILED")
        void whenRecordFailure_withNullNextRetry_thenStatusIsFailed() {
            // Given
            seedProcessedEvent("txn-fail-002");
            repository.save(pendingEntry("outbox-fail-002", "txn-fail-002", "LEDGER"));

            // When
            boolean recorded = repository.recordFailure("outbox-fail-002", "HTTP 400", 400, null);

            // Then
            assertThat(recorded).isTrue();
            assertThat(repository.findById("outbox-fail-002").get().getStatus())
                    .isEqualTo(OutboxStatus.FAILED);
        }
    }

    // -------------------------------------------------------------------------
    // markFailed
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("given markFailed")
    class GivenMarkFailed {

        @Test
        @DisplayName("when markFailed is called, then status is FAILED and lastError is set")
        void whenMarkFailed_thenStatusIsFailedAndLastErrorIsSet() {
            // Given
            seedProcessedEvent("txn-mf-001");
            repository.save(pendingEntry("outbox-mf-001", "txn-mf-001", "LEDGER"));

            // When
            boolean failed = repository.markFailed("outbox-mf-001", "Permanent error");

            // Then
            assertThat(failed).isTrue();
            OutboxEntry entry = repository.findById("outbox-mf-001").get();
            assertThat(entry.getStatus()).isEqualTo(OutboxStatus.FAILED);
            assertThat(entry.getLastError()).isEqualTo("Permanent error");
        }
    }

    // -------------------------------------------------------------------------
    // countByStatus / countByDestination
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("given entries with mixed statuses and destinations")
    class GivenCountEntries {

        @Test
        @DisplayName("when countByStatus PENDING, then returns correct count")
        void whenCountByStatusPending_thenReturnsCorrectCount() {
            // Given
            seedProcessedEvent("txn-cnt-001");
            repository.save(pendingEntry("outbox-cnt-001", "txn-cnt-001", "LEDGER"));
            seedProcessedEvent("txn-cnt-002");
            repository.save(pendingEntry("outbox-cnt-002", "txn-cnt-002", "LEDGER"));
            seedProcessedEvent("txn-cnt-003");
            OutboxEntry delivered = OutboxEntry.builder()
                    .id("outbox-cnt-003").idempotencyKey("txn-cnt-003")
                    .destination("LEDGER").payload("{}").status(OutboxStatus.DELIVERED).build();
            repository.save(delivered);

            // When
            long count = repository.countByStatus(OutboxStatus.PENDING);

            // Then
            assertThat(count).isGreaterThanOrEqualTo(2);
        }

        @Test
        @DisplayName("when countByDestination, then returns count for that destination only")
        void whenCountByDestination_thenReturnsCountForThatDestination() {
            // Given
            seedProcessedEvent("txn-dest-001");
            repository.save(pendingEntry("outbox-dest-001", "txn-dest-001", "LEDGER"));
            seedProcessedEvent("txn-dest-002");
            repository.save(pendingEntry("outbox-dest-002", "txn-dest-002", "LEDGER"));
            seedProcessedEvent("txn-dest-003");
            repository.save(pendingEntry("outbox-dest-003", "txn-dest-003", "AUDIT"));

            // When / Then
            assertThat(repository.countByDestination("LEDGER")).isGreaterThanOrEqualTo(2);
            assertThat(repository.countByDestination("AUDIT")).isGreaterThanOrEqualTo(1);
        }
    }

    // -------------------------------------------------------------------------
    // deleteDeliveredBefore
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("given old DELIVERED and recent DELIVERED entries")
    class GivenMixedAgeDeliveredEntries {

        @Test
        @DisplayName("when deleteDeliveredBefore, then removes only old DELIVERED entries")
        void whenDeleteDeliveredBefore_thenRemovesOnlyOldEntries() {
            // Given
            Instant thirtyDaysAgo = Instant.now().minus(30, ChronoUnit.DAYS);

            // Old delivered entry — set deliveredAt directly in entity
            seedProcessedEvent("txn-old-001");
            com.dev.payments.framework.jpa.entity.OutboxEntryEntity oldEntity =
                    new com.dev.payments.framework.jpa.entity.OutboxEntryEntity();
            oldEntity.setId("outbox-old-del-001");
            oldEntity.setIdempotencyKey("txn-old-001");
            oldEntity.setStatus(OutboxStatus.DELIVERED);
            oldEntity.setDestination("LEDGER");
            oldEntity.setPayload("{}");
            oldEntity.setCreatedAt(thirtyDaysAgo.minus(2, ChronoUnit.HOURS));
            oldEntity.setDeliveredAt(thirtyDaysAgo.minus(1, ChronoUnit.HOURS));
            outboxJpaRepository.save(oldEntity);

            // Recent delivered entry
            seedProcessedEvent("txn-recent-001");
            com.dev.payments.framework.jpa.entity.OutboxEntryEntity recentEntity =
                    new com.dev.payments.framework.jpa.entity.OutboxEntryEntity();
            recentEntity.setId("outbox-recent-del-001");
            recentEntity.setIdempotencyKey("txn-recent-001");
            recentEntity.setStatus(OutboxStatus.DELIVERED);
            recentEntity.setDestination("LEDGER");
            recentEntity.setPayload("{}");
            recentEntity.setCreatedAt(Instant.now().minusSeconds(300));
            recentEntity.setDeliveredAt(Instant.now().minusSeconds(60));
            outboxJpaRepository.save(recentEntity);

            // When
            int deleted = repository.deleteDeliveredBefore(thirtyDaysAgo);

            // Then
            assertThat(deleted).isGreaterThanOrEqualTo(1);
            assertThat(repository.findById("outbox-old-del-001")).isEmpty();
            assertThat(repository.findById("outbox-recent-del-001")).isPresent();
        }

        @Test
        @DisplayName("when deleteDeliveredBefore, then does NOT delete PENDING or FAILED entries")
        void whenDeleteDeliveredBefore_thenDoesNotDeleteNonDeliveredEntries() {
            // Given
            seedProcessedEvent("txn-keep-001");
            repository.save(pendingEntry("outbox-keep-001", "txn-keep-001", "LEDGER"));

            // When
            repository.deleteDeliveredBefore(Instant.now());

            // Then
            assertThat(repository.findById("outbox-keep-001")).isPresent();
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void seedProcessedEvent(String key) {
        processedEventRepository.save(ProcessedEvent.builder()
                .idempotencyKey(key)
                .state(PaymentState.PROCESSING)
                .source("QUEUE_A")
                .destination("LEDGER")
                .receivedAt(Instant.now())
                .build());
    }

    private OutboxEntry pendingEntry(String id, String idempotencyKey, String destination) {
        return OutboxEntry.builder()
                .id(id)
                .idempotencyKey(idempotencyKey)
                .destination(destination)
                .payload("{\"amount\":100}")
                .build();
    }
}
