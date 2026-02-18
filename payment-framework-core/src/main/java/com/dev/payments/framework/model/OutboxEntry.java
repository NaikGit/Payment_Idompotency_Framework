package com.dev.payments.framework.model;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Represents an entry in the outbox table for reliable delivery.
 * 
 * The outbox pattern ensures that:
 * 1. Message processing and outbox entry are in the same transaction
 * 2. Delivery happens asynchronously via the outbox processor
 * 3. Retries are handled reliably with backoff
 */
public class OutboxEntry {
    
    /**
     * Unique identifier for this outbox entry.
     */
    private String id;
    
    /**
     * Links back to the ProcessedEvent.
     */
    private String idempotencyKey;
    
    /**
     * Current status of the outbox entry.
     */
    private OutboxStatus status;
    
    /**
     * Target destination identifier.
     */
    private String destination;
    
    /**
     * HTTP method for REST destinations.
     */
    private String httpMethod;
    
    /**
     * Endpoint URL or topic name.
     */
    private String endpoint;
    
    /**
     * Payload to deliver (JSON serialized).
     */
    private String payload;
    
    /**
     * Headers to include with the request.
     */
    private Map<String, String> headers = new HashMap<>();
    
    /**
     * When this entry was created.
     */
    private Instant createdAt;
    
    /**
     * When delivery was last attempted.
     */
    private Instant lastAttemptAt;
    
    /**
     * When successfully delivered.
     */
    private Instant deliveredAt;
    
    /**
     * Next scheduled retry time.
     */
    private Instant nextRetryAt;
    
    /**
     * Number of delivery attempts.
     */
    private int retryCount;
    
    /**
     * Last error message.
     */
    private String lastError;
    
    /**
     * HTTP status code from last attempt.
     */
    private Integer lastHttpStatus;
    
    /**
     * Node/instance that locked this entry for processing.
     */
    private String lockedBy;
    
    /**
     * When the lock expires.
     */
    private Instant lockExpiresAt;
    
    /**
     * Version for optimistic locking.
     */
    private Long version;
    
    public enum OutboxStatus {
        /**
         * Ready for delivery.
         */
        PENDING,
        
        /**
         * Currently being processed by outbox processor.
         */
        PROCESSING,
        
        /**
         * Successfully delivered.
         */
        DELIVERED,
        
        /**
         * Failed, awaiting retry.
         */
        RETRY_PENDING,
        
        /**
         * Permanently failed (max retries exceeded).
         */
        FAILED
    }
    
    // Default constructor
    public OutboxEntry() {
    }
    
    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private final OutboxEntry entry = new OutboxEntry();
        
        public Builder id(String id) {
            entry.id = id;
            return this;
        }
        
        public Builder idempotencyKey(String idempotencyKey) {
            entry.idempotencyKey = idempotencyKey;
            return this;
        }
        
        public Builder status(OutboxStatus status) {
            entry.status = status;
            return this;
        }
        
        public Builder destination(String destination) {
            entry.destination = destination;
            return this;
        }
        
        public Builder httpMethod(String httpMethod) {
            entry.httpMethod = httpMethod;
            return this;
        }
        
        public Builder endpoint(String endpoint) {
            entry.endpoint = endpoint;
            return this;
        }
        
        public Builder payload(String payload) {
            entry.payload = payload;
            return this;
        }
        
        public Builder headers(Map<String, String> headers) {
            entry.headers = headers != null ? new HashMap<>(headers) : new HashMap<>();
            return this;
        }
        
        public Builder addHeader(String key, String value) {
            entry.headers.put(key, value);
            return this;
        }
        
        public Builder createdAt(Instant createdAt) {
            entry.createdAt = createdAt;
            return this;
        }
        
        public OutboxEntry build() {
            Objects.requireNonNull(entry.idempotencyKey, "idempotencyKey is required");
            Objects.requireNonNull(entry.destination, "destination is required");
            Objects.requireNonNull(entry.payload, "payload is required");
            
            if (entry.id == null) {
                entry.id = java.util.UUID.randomUUID().toString();
            }
            if (entry.status == null) {
                entry.status = OutboxStatus.PENDING;
            }
            if (entry.createdAt == null) {
                entry.createdAt = Instant.now();
            }
            if (entry.httpMethod == null) {
                entry.httpMethod = "POST";
            }
            
            return entry;
        }
    }
    
    // Getters and Setters
    
    public String getId() {
        return id;
    }
    
    public void setId(String id) {
        this.id = id;
    }
    
    public String getIdempotencyKey() {
        return idempotencyKey;
    }
    
    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }
    
    public OutboxStatus getStatus() {
        return status;
    }
    
    public void setStatus(OutboxStatus status) {
        this.status = status;
    }
    
    public String getDestination() {
        return destination;
    }
    
    public void setDestination(String destination) {
        this.destination = destination;
    }
    
    public String getHttpMethod() {
        return httpMethod;
    }
    
    public void setHttpMethod(String httpMethod) {
        this.httpMethod = httpMethod;
    }
    
    public String getEndpoint() {
        return endpoint;
    }
    
    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }
    
    public String getPayload() {
        return payload;
    }
    
    public void setPayload(String payload) {
        this.payload = payload;
    }
    
    public Map<String, String> getHeaders() {
        return headers;
    }
    
    public void setHeaders(Map<String, String> headers) {
        this.headers = headers;
    }
    
    public Instant getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
    
    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }
    
    public void setLastAttemptAt(Instant lastAttemptAt) {
        this.lastAttemptAt = lastAttemptAt;
    }
    
    public Instant getDeliveredAt() {
        return deliveredAt;
    }
    
    public void setDeliveredAt(Instant deliveredAt) {
        this.deliveredAt = deliveredAt;
    }
    
    public Instant getNextRetryAt() {
        return nextRetryAt;
    }
    
    public void setNextRetryAt(Instant nextRetryAt) {
        this.nextRetryAt = nextRetryAt;
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
    
    public Integer getLastHttpStatus() {
        return lastHttpStatus;
    }
    
    public void setLastHttpStatus(Integer lastHttpStatus) {
        this.lastHttpStatus = lastHttpStatus;
    }
    
    public String getLockedBy() {
        return lockedBy;
    }
    
    public void setLockedBy(String lockedBy) {
        this.lockedBy = lockedBy;
    }
    
    public Instant getLockExpiresAt() {
        return lockExpiresAt;
    }
    
    public void setLockExpiresAt(Instant lockExpiresAt) {
        this.lockExpiresAt = lockExpiresAt;
    }
    
    public Long getVersion() {
        return version;
    }
    
    public void setVersion(Long version) {
        this.version = version;
    }
    
    /**
     * Check if this entry is ready for processing.
     */
    public boolean isReadyForProcessing() {
        if (status == OutboxStatus.PENDING) {
            return true;
        }
        if (status == OutboxStatus.RETRY_PENDING && nextRetryAt != null) {
            return Instant.now().isAfter(nextRetryAt);
        }
        return false;
    }
    
    /**
     * Check if lock is still valid.
     */
    public boolean isLocked() {
        return lockedBy != null && lockExpiresAt != null && Instant.now().isBefore(lockExpiresAt);
    }
    
    /**
     * Calculate next retry time using exponential backoff.
     */
    public Instant calculateNextRetry(int baseDelaySeconds, int maxDelaySeconds) {
        int delay = Math.min(baseDelaySeconds * (int) Math.pow(2, retryCount), maxDelaySeconds);
        return Instant.now().plusSeconds(delay);
    }
    
    /**
     * Record a failed delivery attempt.
     */
    public void recordFailure(String error, Integer httpStatus, int maxRetries, int baseDelaySeconds, int maxDelaySeconds) {
        this.retryCount++;
        this.lastError = error;
        this.lastHttpStatus = httpStatus;
        this.lastAttemptAt = Instant.now();
        this.lockedBy = null;
        this.lockExpiresAt = null;
        
        if (retryCount >= maxRetries) {
            this.status = OutboxStatus.FAILED;
        } else {
            this.status = OutboxStatus.RETRY_PENDING;
            this.nextRetryAt = calculateNextRetry(baseDelaySeconds, maxDelaySeconds);
        }
    }
    
    /**
     * Mark as delivered.
     */
    public void markDelivered() {
        this.status = OutboxStatus.DELIVERED;
        this.deliveredAt = Instant.now();
        this.lastAttemptAt = Instant.now();
        this.lockedBy = null;
        this.lockExpiresAt = null;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        OutboxEntry that = (OutboxEntry) o;
        return Objects.equals(id, that.id);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
    
    @Override
    public String toString() {
        return "OutboxEntry{" +
                "id='" + id + '\'' +
                ", idempotencyKey='" + idempotencyKey + '\'' +
                ", status=" + status +
                ", destination='" + destination + '\'' +
                ", retryCount=" + retryCount +
                '}';
    }
}
