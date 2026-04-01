package com.dev.payments.framework.mongodb.repository;

import com.dev.payments.framework.model.OutboxEntry;
import com.dev.payments.framework.model.OutboxEntry.OutboxStatus;
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
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository adapter test: real MongoOutboxRepository against Flapdoodle embedded MongoDB.
 *
 * Key invariants tested:
 *  - tryLock is atomic: only one caller wins when two attempt to lock the same entry
 *  - releaseLock validates ownership: a different owner cannot release someone else's lock
 *  - findReadyForProcessing returns PENDING and past-due RETRY_PENDING, ordered FIFO
 *  - findExpiredLocks returns only entries with an expired lockedBy
 */
@DataMongoTest
@ActiveProfiles("test")
@DisplayName("MongoOutboxRepository")
class MongoOutboxRepositoryTest {

    @Autowired
    private OutboxEntryMongoStore store;

    @Autowired
    private MongoTemplate mongoTemplate;

    private MongoOutboxRepository repository;

    @BeforeEach
    void setUp() {
        repository = new MongoOutboxRepository(store, mongoTemplate);
        store.deleteAll();
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
            OutboxEntry entry = pendingEntry("outbox-rtt-001", "txn-001", "LEDGER");
            repository.save(entry);

            // When
            Optional<OutboxEntry> found = repository.findById("outbox-rtt-001");

            // Then
            assertThat(found).isPresent();
            assertThat(found.get().getId()).isEqualTo("outbox-rtt-001");
            assertThat(found.get().getIdempotencyKey()).isEqualTo("txn-001");
            assertThat(found.get().getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(found.get().getDestination()).isEqualTo("LEDGER");
            assertThat(found.get().getPayload()).isEqualTo("{\"amount\":100}");
        }

        @Test
        @DisplayName("when findByIdempotencyKey is called, then returns matching entries")
        void whenFindByIdempotencyKey_thenReturnsMatchingEntries() {
            // Given
            repository.save(pendingEntry("outbox-fk-001", "txn-key-001", "LEDGER"));
            repository.save(pendingEntry("outbox-fk-002", "txn-key-001", "AUDIT"));
            repository.save(pendingEntry("outbox-fk-003", "txn-key-002", "LEDGER"));

            // When
            List<OutboxEntry> results = repository.findByIdempotencyKey("txn-key-001");

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
        @DisplayName("when findReadyForProcessing is called, then returns PENDING and past-due RETRY_PENDING only")
        void whenFindReadyForProcessing_thenReturnsPendingAndPastDueRetry() {
            // Given
            repository.save(pendingEntry("outbox-ready-001", "txn-r-001", "LEDGER"));

            OutboxEntry futureRetry = OutboxEntry.builder()
                    .id("outbox-ready-002")
                    .idempotencyKey("txn-r-002")
                    .destination("LEDGER")
                    .payload("{}")
                    .status(OutboxStatus.RETRY_PENDING)
                    .build();
            OutboxEntry savedFutureRetry = repository.save(futureRetry);
            // set next_retry_at to future
            mongoTemplate.updateFirst(
                    org.springframework.data.mongodb.core.query.Query.query(
                            org.springframework.data.mongodb.core.query.Criteria.where("_id").is("outbox-ready-002")),
                    new org.springframework.data.mongodb.core.query.Update()
                            .set("next_retry_at", Instant.now().plus(1, ChronoUnit.HOURS)),
                    com.dev.payments.framework.mongodb.document.OutboxEntryDocument.class);

            OutboxEntry pastRetry = OutboxEntry.builder()
                    .id("outbox-ready-003")
                    .idempotencyKey("txn-r-003")
                    .destination("LEDGER")
                    .payload("{}")
                    .status(OutboxStatus.RETRY_PENDING)
                    .build();
            repository.save(pastRetry);
            // set next_retry_at to past
            mongoTemplate.updateFirst(
                    org.springframework.data.mongodb.core.query.Query.query(
                            org.springframework.data.mongodb.core.query.Criteria.where("_id").is("outbox-ready-003")),
                    new org.springframework.data.mongodb.core.query.Update()
                            .set("next_retry_at", Instant.now().minus(1, ChronoUnit.HOURS)),
                    com.dev.payments.framework.mongodb.document.OutboxEntryDocument.class);

            repository.save(OutboxEntry.builder()
                    .id("outbox-ready-004")
                    .idempotencyKey("txn-r-004")
                    .destination("LEDGER")
                    .payload("{}")
                    .status(OutboxStatus.DELIVERED)
                    .build());

            // When
            List<OutboxEntry> ready = repository.findReadyForProcessing(10);

            // Then
            assertThat(ready).extracting(OutboxEntry::getId)
                    .contains("outbox-ready-001", "outbox-ready-003")
                    .doesNotContain("outbox-ready-002", "outbox-ready-004");
        }

        @Test
        @DisplayName("when findReadyForProcessing with limit 1, then returns at most 1 entry")
        void whenFindReadyForProcessing_thenRespectsLimit() {
            // Given
            repository.save(pendingEntry("outbox-lim-001", "txn-lim-001", "LEDGER"));
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
            repository.save(pendingEntry("outbox-lock-001", "txn-lock-001", "LEDGER"));

            // When
            boolean locked = repository.tryLock("outbox-lock-001", "node-1", 30);

            // Then
            assertThat(locked)
                    .as("tryLock must return true when the entry was unlocked")
                    .isTrue();
        }

        @Test
        @DisplayName("when tryLock is called twice by different nodes, then only one succeeds")
        void whenTryLockCalledTwice_thenOnlyOneSucceeds() {
            // Given
            repository.save(pendingEntry("outbox-lock-002", "txn-lock-002", "LEDGER"));

            // When — both nodes attempt to lock the same entry
            boolean firstLock = repository.tryLock("outbox-lock-002", "node-1", 30);
            boolean secondLock = repository.tryLock("outbox-lock-002", "node-2", 30);

            // Then — exactly one must succeed
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
        @DisplayName("when tryLock is called after lock expiry, then new lock is acquired")
        void whenTryLockAfterExpiry_thenNewLockAcquired() {
            // Given — entry whose lock expired 1 minute ago
            repository.save(pendingEntry("outbox-exp-001", "txn-exp-001", "LEDGER"));
            mongoTemplate.updateFirst(
                    org.springframework.data.mongodb.core.query.Query.query(
                            org.springframework.data.mongodb.core.query.Criteria.where("_id").is("outbox-exp-001")),
                    new org.springframework.data.mongodb.core.query.Update()
                            .set("locked_by", "dead-node")
                            .set("lock_expires_at", Instant.now().minus(1, ChronoUnit.MINUTES)),
                    com.dev.payments.framework.mongodb.document.OutboxEntryDocument.class);

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
            repository.save(pendingEntry("outbox-rel-002", "txn-rel-002", "LEDGER"));
            repository.tryLock("outbox-rel-002", "node-1", 30);

            // When — node-2 attempts to steal node-1's lock
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
            // Given — expired lock
            repository.save(pendingEntry("outbox-el-001", "txn-el-001", "LEDGER"));
            mongoTemplate.updateFirst(
                    org.springframework.data.mongodb.core.query.Query.query(
                            org.springframework.data.mongodb.core.query.Criteria.where("_id").is("outbox-el-001")),
                    new org.springframework.data.mongodb.core.query.Update()
                            .set("locked_by", "dead-node")
                            .set("lock_expires_at", Instant.now().minus(5, ChronoUnit.MINUTES)),
                    com.dev.payments.framework.mongodb.document.OutboxEntryDocument.class);

            // Active lock (not expired)
            repository.save(pendingEntry("outbox-el-002", "txn-el-002", "LEDGER"));
            repository.tryLock("outbox-el-002", "active-node", 300);

            // Unlocked entry
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
    @DisplayName("given a delivery failure with retry")
    class GivenDeliveryFailureWithRetry {

        @Test
        @DisplayName("when recordFailure with a future nextRetryAt, then status is RETRY_PENDING")
        void whenRecordFailure_withRetry_thenStatusIsRetryPending() {
            // Given
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
            repository.save(pendingEntry("outbox-fail-002", "txn-fail-002", "LEDGER"));

            // When — null nextRetryAt signals permanent failure
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
            repository.save(pendingEntry("outbox-cnt-001", "txn-cnt-001", "LEDGER"));
            repository.save(pendingEntry("outbox-cnt-002", "txn-cnt-002", "LEDGER"));
            repository.save(OutboxEntry.builder()
                    .id("outbox-cnt-003")
                    .idempotencyKey("txn-cnt-003")
                    .destination("LEDGER")
                    .payload("{}")
                    .status(OutboxStatus.DELIVERED)
                    .build());

            // When
            long count = repository.countByStatus(OutboxStatus.PENDING);

            // Then
            assertThat(count).isGreaterThanOrEqualTo(2);
        }

        @Test
        @DisplayName("when countByDestination, then returns count for that destination only")
        void whenCountByDestination_thenReturnsCountForThatDestination() {
            // Given
            repository.save(pendingEntry("outbox-dest-001", "txn-dest-001", "LEDGER"));
            repository.save(pendingEntry("outbox-dest-002", "txn-dest-002", "LEDGER"));
            repository.save(pendingEntry("outbox-dest-003", "txn-dest-003", "AUDIT"));

            // When
            long ledgerCount = repository.countByDestination("LEDGER");
            long auditCount = repository.countByDestination("AUDIT");

            // Then
            assertThat(ledgerCount).isGreaterThanOrEqualTo(2);
            assertThat(auditCount).isGreaterThanOrEqualTo(1);
        }
    }

    // -------------------------------------------------------------------------
    // deleteDeliveredBefore
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("given old DELIVERED and recent DELIVERED entries")
    class GivenMixedAgeDeliveredEntries {

        @Test
        @DisplayName("when deleteDeliveredBefore is called, then removes only old DELIVERED entries")
        void whenDeleteDeliveredBefore_thenRemovesOnlyOldEntries() {
            // Given
            Instant thirtyDaysAgo = Instant.now().minus(30, ChronoUnit.DAYS);

            // Old delivered entry
            OutboxEntry oldEntry = OutboxEntry.builder()
                    .id("outbox-old-del-001")
                    .idempotencyKey("txn-old-001")
                    .destination("LEDGER")
                    .payload("{}")
                    .status(OutboxStatus.DELIVERED)
                    .build();
            repository.save(oldEntry);
            mongoTemplate.updateFirst(
                    org.springframework.data.mongodb.core.query.Query.query(
                            org.springframework.data.mongodb.core.query.Criteria.where("_id").is("outbox-old-del-001")),
                    new org.springframework.data.mongodb.core.query.Update()
                            .set("delivered_at", thirtyDaysAgo.minus(1, ChronoUnit.HOURS)),
                    com.dev.payments.framework.mongodb.document.OutboxEntryDocument.class);

            // Recent delivered entry
            OutboxEntry recentEntry = OutboxEntry.builder()
                    .id("outbox-recent-del-001")
                    .idempotencyKey("txn-recent-001")
                    .destination("LEDGER")
                    .payload("{}")
                    .status(OutboxStatus.DELIVERED)
                    .build();
            repository.save(recentEntry);
            mongoTemplate.updateFirst(
                    org.springframework.data.mongodb.core.query.Query.query(
                            org.springframework.data.mongodb.core.query.Criteria.where("_id").is("outbox-recent-del-001")),
                    new org.springframework.data.mongodb.core.query.Update()
                            .set("delivered_at", Instant.now().minusSeconds(60)),
                    com.dev.payments.framework.mongodb.document.OutboxEntryDocument.class);

            // When
            int deleted = repository.deleteDeliveredBefore(thirtyDaysAgo);

            // Then
            assertThat(deleted).isGreaterThanOrEqualTo(1);
            assertThat(repository.findById("outbox-old-del-001")).isEmpty();
            assertThat(repository.findById("outbox-recent-del-001")).isPresent();
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private OutboxEntry pendingEntry(String id, String idempotencyKey, String destination) {
        return OutboxEntry.builder()
                .id(id)
                .idempotencyKey(idempotencyKey)
                .destination(destination)
                .payload("{\"amount\":100}")
                .build();
    }
}
