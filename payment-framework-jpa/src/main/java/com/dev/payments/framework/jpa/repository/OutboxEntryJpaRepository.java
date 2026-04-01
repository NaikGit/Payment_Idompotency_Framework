package com.dev.payments.framework.jpa.repository;

import com.dev.payments.framework.jpa.entity.OutboxEntryEntity;
import com.dev.payments.framework.model.OutboxEntry.OutboxStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Spring Data JPA repository for OutboxEntryEntity.
 *
 * All custom queries mirror the column semantics in database/oracle/V1__create_payment_tables.sql.
 * The tryLock CAS is implemented in JpaOutboxRepository via a single @Modifying @Query to guarantee
 * atomicity — it cannot live here as a derived method.
 */
@Repository
public interface OutboxEntryJpaRepository extends JpaRepository<OutboxEntryEntity, String> {

    List<OutboxEntryEntity> findByIdempotencyKey(String idempotencyKey);

    List<OutboxEntryEntity> findByStatus(OutboxStatus status, Pageable pageable);

    long countByStatus(OutboxStatus status);

    long countByDestination(String destination);

    /**
     * FIFO: PENDING entries plus RETRY_PENDING whose next_retry_at is in the past.
     * ORDER BY created_at ASC ensures fair processing order.
     */
    @Query("""
            SELECT e FROM OutboxEntryEntity e
            WHERE e.status = 'PENDING'
               OR (e.status = 'RETRY_PENDING' AND e.nextRetryAt <= :now)
            ORDER BY e.createdAt ASC
            """)
    List<OutboxEntryEntity> findReadyForProcessing(@Param("now") Instant now, Pageable pageable);

    /**
     * Recovery query: entries locked by a processor that has since died.
     */
    @Query("""
            SELECT e FROM OutboxEntryEntity e
            WHERE e.lockedBy IS NOT NULL AND e.lockExpiresAt < :now
            """)
    List<OutboxEntryEntity> findExpiredLocks(@Param("now") Instant now);

    /**
     * Atomic CAS lock acquisition.
     *
     * Conditions: entry is unlocked (lockedBy IS NULL) OR lock has expired.
     * Returns 1 if the update applied, 0 if another node won the race.
     */
    @Modifying
    @Query("""
            UPDATE OutboxEntryEntity e
            SET e.lockedBy = :lockedBy,
                e.lockExpiresAt = :lockExpiresAt,
                e.status = 'PROCESSING'
            WHERE e.id = :id
              AND (e.lockedBy IS NULL OR e.lockExpiresAt < :now)
            """)
    int tryLock(@Param("id") String entryId,
                @Param("lockedBy") String lockedBy,
                @Param("lockExpiresAt") Instant lockExpiresAt,
                @Param("now") Instant now);

    /**
     * Ownership-validated lock release.
     *
     * The AND lockedBy = :lockedBy guard prevents a crashed node from clearing
     * a lock that was already re-acquired by a different node after expiry.
     */
    @Modifying
    @Query("""
            UPDATE OutboxEntryEntity e
            SET e.lockedBy = NULL, e.lockExpiresAt = NULL
            WHERE e.id = :id AND e.lockedBy = :lockedBy
            """)
    int releaseLock(@Param("id") String entryId, @Param("lockedBy") String lockedBy);

    @Modifying
    @Query("""
            UPDATE OutboxEntryEntity e
            SET e.status = 'DELIVERED', e.deliveredAt = :deliveredAt,
                e.lastAttemptAt = :deliveredAt,
                e.lockedBy = NULL, e.lockExpiresAt = NULL
            WHERE e.id = :id
            """)
    int markDelivered(@Param("id") String entryId, @Param("deliveredAt") Instant deliveredAt);

    @Modifying
    @Query("""
            UPDATE OutboxEntryEntity e
            SET e.status = :status,
                e.lastError = :error,
                e.lastHttpStatus = :httpStatus,
                e.lastAttemptAt = :now,
                e.nextRetryAt = :nextRetryAt,
                e.retryCount = e.retryCount + 1,
                e.lockedBy = NULL, e.lockExpiresAt = NULL
            WHERE e.id = :id
            """)
    int recordFailure(@Param("id") String entryId,
                      @Param("error") String error,
                      @Param("httpStatus") Integer httpStatus,
                      @Param("now") Instant now,
                      @Param("nextRetryAt") Instant nextRetryAt,
                      @Param("status") OutboxStatus status);

    @Modifying
    @Query("""
            UPDATE OutboxEntryEntity e
            SET e.status = 'FAILED', e.lastError = :error,
                e.lastAttemptAt = :now,
                e.lockedBy = NULL, e.lockExpiresAt = NULL
            WHERE e.id = :id
            """)
    int markFailed(@Param("id") String entryId,
                   @Param("error") String error,
                   @Param("now") Instant now);

    @Modifying
    @Query("DELETE FROM OutboxEntryEntity e WHERE e.status = 'DELIVERED' AND e.deliveredAt < :olderThan")
    int deleteDeliveredBefore(@Param("olderThan") Instant olderThan);

    @Query("""
            SELECT AVG(CAST(FUNCTION('TIMESTAMPDIFF', SECOND, e.createdAt, e.deliveredAt) AS double) * 1000)
            FROM OutboxEntryEntity e
            WHERE e.status = 'DELIVERED' AND e.deliveredAt >= :since
            """)
    Double getAverageDeliveryTimeMillis(@Param("since") Instant since);
}
