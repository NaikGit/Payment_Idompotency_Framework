package com.dev.payments.framework.jpa.entity;

import com.dev.payments.framework.state.PaymentState;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * JPA entity for ProcessedEvent.
 * 
 * Table Schema:
 * <pre>
 * CREATE TABLE processed_events (
 *     idempotency_key VARCHAR2(255) PRIMARY KEY,
 *     state VARCHAR2(50) NOT NULL,
 *     source VARCHAR2(255),
 *     destination VARCHAR2(255),
 *     received_at TIMESTAMP NOT NULL,
 *     completed_at TIMESTAMP,
 *     processed_by VARCHAR2(255),
 *     retry_count NUMBER DEFAULT 0,
 *     last_error VARCHAR2(2000),
 *     version NUMBER DEFAULT 0
 * );
 * 
 * CREATE INDEX idx_processed_events_state ON processed_events(state);
 * CREATE INDEX idx_processed_events_received_at ON processed_events(received_at);
 * </pre>
 */
@Entity
@Table(name = "processed_events", indexes = {
        @Index(name = "idx_processed_events_state", columnList = "state"),
        @Index(name = "idx_processed_events_received_at", columnList = "received_at")
})
public class ProcessedEventEntity {
    
    @Id
    @Column(name = "idempotency_key", length = 255, nullable = false)
    private String idempotencyKey;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "state", length = 50, nullable = false)
    private PaymentState state;
    
    @Column(name = "source", length = 255)
    private String source;
    
    @Column(name = "destination", length = 255)
    private String destination;
    
    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;
    
    @Column(name = "completed_at")
    private Instant completedAt;
    
    @Column(name = "processed_by", length = 255)
    private String processedBy;
    
    @Column(name = "retry_count")
    private int retryCount = 0;
    
    @Column(name = "last_error", length = 2000)
    private String lastError;
    
    @Version
    @Column(name = "version")
    private Long version;
    
    // Default constructor for JPA
    public ProcessedEventEntity() {
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
