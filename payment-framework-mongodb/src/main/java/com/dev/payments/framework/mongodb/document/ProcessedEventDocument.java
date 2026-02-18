package com.dev.payments.framework.mongodb.document;

import com.dev.payments.framework.state.PaymentState;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * MongoDB document for ProcessedEvent.
 * 
 * Collection: processed_events
 * 
 * Indexes:
 * - _id (idempotencyKey) - unique, primary
 * - state - for querying by state
 * - receivedAt - for finding stuck/old events
 * - destination - for metrics
 */
@Document(collection = "processed_events")
@CompoundIndexes({
    @CompoundIndex(name = "idx_state_received", def = "{'state': 1, 'receivedAt': 1}")
})
public class ProcessedEventDocument {
    
    /**
     * The idempotency key is the document ID.
     * This ensures uniqueness at the database level.
     */
    @Id
    private String idempotencyKey;
    
    @Indexed
    private PaymentState state;
    
    private String source;
    
    @Indexed
    private String destination;
    
    @Indexed
    private Instant receivedAt;
    
    private Instant completedAt;
    
    private String processedBy;
    
    private int retryCount;
    
    private String lastError;
    
    @Version
    private Long version;
    
    // Default constructor for MongoDB
    public ProcessedEventDocument() {
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
}
