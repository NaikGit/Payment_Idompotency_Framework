package com.dev.payments.framework.jpa.repository;

import com.dev.payments.framework.jpa.entity.OutboxEntryEntity;
import com.dev.payments.framework.model.OutboxEntry.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Spring Data JPA repository for OutboxEntryEntity.
 */
@Repository
public interface OutboxEntryJpaRepository extends JpaRepository<OutboxEntryEntity, String> {
    
    List<OutboxEntryEntity> findByIdempotencyKey(String idempotencyKey);
    
    @Query("SELECT e FROM OutboxEntryEntity e WHERE " +
           "(e.status = 'PENDING' OR (e.status = 'RETRY_PENDING' AND e.nextRetryAt <= :now)) " +
           "AND (e.lockedBy IS NULL OR e.lockExpiresAt <= :now) " +
           "ORDER BY e.createdAt ASC")
    List<OutboxEntryEntity> findReadyForProcessing(@Param("now") Instant now, org.springframework.data.domain.Pageable pageable);
    
    @Modifying
    @Query("UPDATE OutboxEntryEntity e SET e.lockedBy = :lockedBy, e.lockExpiresAt = :lockExpires, e.status = 'PROCESSING' " +
           "WHERE e.id = :id AND (e.lockedBy IS NULL OR e.lockExpiresAt <= :now)")
    int tryLock(@Param("id") String id, @Param("lockedBy") String lockedBy, 
                @Param("lockExpires") Instant lockExpires, @Param("now") Instant now);
    
    @Modifying
    @Query("UPDATE OutboxEntryEntity e SET e.lockedBy = NULL, e.lockExpiresAt = NULL WHERE e.id = :id AND e.lockedBy = :lockedBy")
    int releaseLock(@Param("id") String id, @Param("lockedBy") String lockedBy);
    
    @Query("SELECT e FROM OutboxEntryEntity e WHERE e.lockedBy IS NOT NULL AND e.lockExpiresAt <= :now")
    List<OutboxEntryEntity> findExpiredLocks(@Param("now") Instant now);
    
    @Modifying
    @Query("UPDATE OutboxEntryEntity e SET e.status = 'DELIVERED', e.deliveredAt = :deliveredAt, e.lastAttemptAt = :deliveredAt, " +
           "e.lockedBy = NULL, e.lockExpiresAt = NULL WHERE e.id = :id")
    int markDelivered(@Param("id") String id, @Param("deliveredAt") Instant deliveredAt);
    
    @Modifying
    @Query("UPDATE OutboxEntryEntity e SET e.status = 'RETRY_PENDING', e.lastError = :error, e.lastHttpStatus = :httpStatus, " +
           "e.lastAttemptAt = :attemptAt, e.nextRetryAt = :nextRetry, e.retryCount = e.retryCount + 1, " +
           "e.lockedBy = NULL, e.lockExpiresAt = NULL WHERE e.id = :id")
    int recordFailure(@Param("id") String id, @Param("error") String error, @Param("httpStatus") Integer httpStatus,
                      @Param("attemptAt") Instant attemptAt, @Param("nextRetry") Instant nextRetry);
    
    @Modifying
    @Query("UPDATE OutboxEntryEntity e SET e.status = 'FAILED', e.lastError = :error, e.lastAttemptAt = :attemptAt, " +
           "e.lockedBy = NULL, e.lockExpiresAt = NULL WHERE e.id = :id")
    int markFailed(@Param("id") String id, @Param("error") String error, @Param("attemptAt") Instant attemptAt);
    
    List<OutboxEntryEntity> findByStatus(OutboxStatus status, org.springframework.data.domain.Pageable pageable);
    
    long countByStatus(OutboxStatus status);
    
    long countByDestination(String destination);
    
    @Modifying
    @Query("DELETE FROM OutboxEntryEntity e WHERE e.status = 'DELIVERED' AND e.deliveredAt < :olderThan")
    int deleteDeliveredBefore(@Param("olderThan") Instant olderThan);
    
    @Query("SELECT AVG(TIMESTAMPDIFF(SECOND, e.createdAt, e.deliveredAt) * 1000) FROM OutboxEntryEntity e " +
           "WHERE e.status = 'DELIVERED' AND e.deliveredAt >= :since")
    Double getAverageDeliveryTimeMillis(@Param("since") Instant since);
}
