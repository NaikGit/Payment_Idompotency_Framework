package com.dev.payments.framework.repository;

import com.dev.payments.framework.model.ProcessedEvent;
import com.dev.payments.framework.state.PaymentState;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Repository interface for ProcessedEvent persistence.
 * Implementations provided for JPA (Oracle) and MongoDB.
 */
public interface ProcessedEventRepository {
    
    /**
     * Find a processed event by its idempotency key.
     * 
     * @param idempotencyKey The unique key
     * @return Optional containing the event if found
     */
    Optional<ProcessedEvent> findByIdempotencyKey(String idempotencyKey);
    
    /**
     * Check if an event with the given key exists.
     * More efficient than findByIdempotencyKey when you only need to check existence.
     * 
     * @param idempotencyKey The unique key
     * @return true if exists
     */
    boolean existsByIdempotencyKey(String idempotencyKey);
    
    /**
     * Save a processed event.
     * For new events, this should fail if the key already exists (idempotency guarantee).
     * 
     * @param event The event to save
     * @return The saved event
     */
    ProcessedEvent save(ProcessedEvent event);
    
    /**
     * Update the state of a processed event.
     * Uses optimistic locking to prevent concurrent updates.
     * 
     * @param idempotencyKey The unique key
     * @param newState The new state
     * @param lastError Optional error message
     * @return true if update succeeded
     */
    boolean updateState(String idempotencyKey, PaymentState newState, String lastError);
    
    /**
     * Mark an event as completed.
     * 
     * @param idempotencyKey The unique key
     * @return true if update succeeded
     */
    boolean markCompleted(String idempotencyKey);
    
    /**
     * Find all events in a given state.
     * 
     * @param state The state to filter by
     * @return List of matching events
     */
    List<ProcessedEvent> findByState(PaymentState state);
    
    /**
     * Find events stuck in PROCESSING state (potential issues).
     * 
     * @param olderThan Only return events received before this time
     * @return List of stuck events
     */
    List<ProcessedEvent> findStuckProcessing(Instant olderThan);
    
    /**
     * Find failed events that may need manual intervention.
     * 
     * @param limit Maximum number to return
     * @return List of failed events
     */
    List<ProcessedEvent> findRecentFailures(int limit);
    
    /**
     * Count events by state (for metrics).
     * 
     * @param state The state to count
     * @return Count of events in that state
     */
    long countByState(PaymentState state);
    
    /**
     * Delete events older than a given time (cleanup).
     * Only deletes COMPLETED events.
     * 
     * @param olderThan Delete events completed before this time
     * @return Number of deleted events
     */
    int deleteCompletedBefore(Instant olderThan);
}
