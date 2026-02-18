package com.dev.payments.framework.repository;

import com.dev.payments.framework.model.OutboxEntry;
import com.dev.payments.framework.model.OutboxEntry.OutboxStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository interface for OutboxEntry persistence.
 * Implementations provided for JPA (Oracle) and MongoDB.
 */
public interface OutboxRepository {
    
    /**
     * Find an outbox entry by its ID.
     * 
     * @param id The entry ID
     * @return Optional containing the entry if found
     */
    Optional<OutboxEntry> findById(String id);
    
    /**
     * Find outbox entries by idempotency key.
     * 
     * @param idempotencyKey The idempotency key
     * @return List of matching entries
     */
    List<OutboxEntry> findByIdempotencyKey(String idempotencyKey);
    
    /**
     * Save an outbox entry.
     * 
     * @param entry The entry to save
     * @return The saved entry
     */
    OutboxEntry save(OutboxEntry entry);
    
    /**
     * Find entries ready for processing.
     * Returns entries with status PENDING or RETRY_PENDING (if retry time has passed).
     * Orders by creation time to ensure FIFO processing.
     * 
     * @param limit Maximum number to return
     * @return List of entries ready for processing
     */
    List<OutboxEntry> findReadyForProcessing(int limit);
    
    /**
     * Atomically lock an entry for processing.
     * Uses database-level locking to prevent concurrent processing.
     * 
     * @param entryId The entry to lock
     * @param lockedBy Identifier of the processor taking the lock
     * @param lockDuration How long the lock should be held (in seconds)
     * @return true if lock was acquired
     */
    boolean tryLock(String entryId, String lockedBy, int lockDuration);
    
    /**
     * Release a lock on an entry.
     * 
     * @param entryId The entry to unlock
     * @param lockedBy Must match the original locker
     * @return true if lock was released
     */
    boolean releaseLock(String entryId, String lockedBy);
    
    /**
     * Find entries with expired locks (for recovery).
     * These are entries that were being processed but the processor died.
     * 
     * @return List of entries with expired locks
     */
    List<OutboxEntry> findExpiredLocks();
    
    /**
     * Mark an entry as delivered.
     * 
     * @param entryId The entry ID
     * @return true if update succeeded
     */
    boolean markDelivered(String entryId);
    
    /**
     * Record a failed delivery attempt.
     * 
     * @param entryId The entry ID
     * @param error The error message
     * @param httpStatus The HTTP status code (if applicable)
     * @param nextRetryAt When to retry
     * @return true if update succeeded
     */
    boolean recordFailure(String entryId, String error, Integer httpStatus, Instant nextRetryAt);
    
    /**
     * Mark an entry as permanently failed.
     * 
     * @param entryId The entry ID
     * @param error The final error message
     * @return true if update succeeded
     */
    boolean markFailed(String entryId, String error);
    
    /**
     * Find entries by status.
     * 
     * @param status The status to filter by
     * @param limit Maximum number to return
     * @return List of matching entries
     */
    List<OutboxEntry> findByStatus(OutboxStatus status, int limit);
    
    /**
     * Count entries by status (for metrics).
     * 
     * @param status The status to count
     * @return Count of entries
     */
    long countByStatus(OutboxStatus status);
    
    /**
     * Count entries by destination (for metrics).
     * 
     * @param destination The destination to count
     * @return Count of entries
     */
    long countByDestination(String destination);
    
    /**
     * Delete delivered entries older than a given time (cleanup).
     * 
     * @param olderThan Delete entries delivered before this time
     * @return Number of deleted entries
     */
    int deleteDeliveredBefore(Instant olderThan);
    
    /**
     * Get average delivery time in milliseconds (for metrics).
     * 
     * @param since Calculate average since this time
     * @return Average delivery time in milliseconds
     */
    double getAverageDeliveryTimeMillis(Instant since);
}
