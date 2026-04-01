package com.dev.payments.framework.model;

import com.dev.payments.framework.state.PaymentState;

import java.time.Instant;
import java.util.Objects;

/**
 * Represents a processed payment event for idempotency tracking.
 * This is the core model that tracks whether a message has been seen before.
 * 
 * Thread Safety: Instances should be treated as effectively immutable once persisted.
 * State changes should go through the repository to ensure database consistency.
 */
public class ProcessedEvent {
    
    /**
     * Unique identifier for idempotency.
     * Typically extracted from the payment message (e.g., transactionId).
     */
    private String idempotencyKey;
    
    /**
     * Current state in the payment lifecycle.
     */
    private PaymentState state;
    
    /**
     * Source identifier (e.g., queue name, topic name).
     */
    private String source;
    
    /**
     * Destination system identifier.
     */
    private String destination;
    
    /**
     * When the event was first received.
     */
    private Instant receivedAt;
    
    /**
     * When processing completed (success or permanent failure).
     */
    private Instant completedAt;
    
    /**
     * Node/instance that first processed this event.
     */
    private String processedBy;
    
    /**
     * Number of delivery attempts.
     */
    private int retryCount;
    
    /**
     * Last error message if failed.
     */
    private String lastError;
    
    /**
     * Version for optimistic locking.
     */
    private Long version;
    
    // Default constructor for frameworks
    public ProcessedEvent() {
    }
    
    // Builder pattern for cleaner construction
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private final ProcessedEvent event = new ProcessedEvent();
        
        public Builder idempotencyKey(String idempotencyKey) {
            event.idempotencyKey = idempotencyKey;
            return this;
        }
        
        public Builder state(PaymentState state) {
            event.state = state;
            return this;
        }
        
        public Builder source(String source) {
            event.source = source;
            return this;
        }
        
        public Builder destination(String destination) {
            event.destination = destination;
            return this;
        }
        
        public Builder receivedAt(Instant receivedAt) {
            event.receivedAt = receivedAt;
            return this;
        }
        
        public Builder completedAt(Instant completedAt) {
            event.completedAt = completedAt;
            return this;
        }
        
        public Builder processedBy(String processedBy) {
            event.processedBy = processedBy;
            return this;
        }
        
        public Builder retryCount(int retryCount) {
            event.retryCount = retryCount;
            return this;
        }
        
        public Builder lastError(String lastError) {
            event.lastError = lastError;
            return this;
        }
        
        public ProcessedEvent build() {
            Objects.requireNonNull(event.idempotencyKey, "idempotencyKey is required");
            Objects.requireNonNull(event.state, "state is required");
            if (event.receivedAt == null) {
                event.receivedAt = Instant.now();
            }
            return event;
        }
    }
    
    // Getters and Setters
    
    public String getIdempotencyKey() {
        return idempotencyKey;
    }
    
    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }
    
    public PaymentState getState() {
        return state;
    }
    
    public void setState(PaymentState state) {
        this.state = state;
    }
    
    public String getSource() {
        return source;
    }
    
    public void setSource(String source) {
        this.source = source;
    }
    
    public String getDestination() {
        return destination;
    }
    
    public void setDestination(String destination) {
        this.destination = destination;
    }
    
    public Instant getReceivedAt() {
        return receivedAt;
    }
    
    public void setReceivedAt(Instant receivedAt) {
        this.receivedAt = receivedAt;
    }
    
    public Instant getCompletedAt() {
        return completedAt;
    }
    
    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }
    
    public String getProcessedBy() {
        return processedBy;
    }
    
    public void setProcessedBy(String processedBy) {
        this.processedBy = processedBy;
    }
    
    public int getRetryCount() {
        return retryCount;
    }
    
    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }
    
    public String getLastError() {
        return lastError;
    }
    
    public void setLastError(String lastError) {
        this.lastError = lastError;
    }
    
    public Long getVersion() {
        return version;
    }
    
    public void setVersion(Long version) {
        this.version = version;
    }
    
    /**
     * Check if this event can be retried.
     */
    public boolean canRetry(int maxRetries) {
        return state.isRetryable() && retryCount < maxRetries;
    }
    
    /**
     * Increment retry count and update error.
     */
    public void recordFailure(String error) {
        this.retryCount++;
        this.lastError = error;
        this.state = PaymentState.FAILED;
    }
    
    /**
     * Mark as completed.
     */
    public void markCompleted() {
        this.state = PaymentState.COMPLETED;
        this.completedAt = Instant.now();
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ProcessedEvent that = (ProcessedEvent) o;
        return Objects.equals(idempotencyKey, that.idempotencyKey);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(idempotencyKey);
    }
    
    @Override
    public String toString() {
        return "ProcessedEvent{" +
                "idempotencyKey='" + idempotencyKey + '\'' +
                ", state=" + state +
                ", retryCount=" + retryCount +
                ", receivedAt=" + receivedAt +
                ", completedAt=" + completedAt +
                '}';
    }
}
