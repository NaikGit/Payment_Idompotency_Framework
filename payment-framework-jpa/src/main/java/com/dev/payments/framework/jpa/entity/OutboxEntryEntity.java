package com.dev.payments.framework.jpa.entity;

import com.dev.payments.framework.model.OutboxEntry.OutboxStatus;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * JPA entity for OutboxEntry.
 * 
 * Table Schema:
 * <pre>
 * CREATE TABLE outbox_entries (
 *     id VARCHAR2(36) PRIMARY KEY,
 *     idempotency_key VARCHAR2(255) NOT NULL,
 *     status VARCHAR2(50) NOT NULL,
 *     destination VARCHAR2(255) NOT NULL,
 *     http_method VARCHAR2(10),
 *     endpoint VARCHAR2(1000),
 *     payload CLOB NOT NULL,
 *     headers CLOB,
 *     created_at TIMESTAMP NOT NULL,
 *     last_attempt_at TIMESTAMP,
 *     delivered_at TIMESTAMP,
 *     next_retry_at TIMESTAMP,
 *     retry_count NUMBER DEFAULT 0,
 *     last_error VARCHAR2(2000),
 *     last_http_status NUMBER,
 *     locked_by VARCHAR2(255),
 *     lock_expires_at TIMESTAMP,
 *     version NUMBER DEFAULT 0,
 *     CONSTRAINT fk_outbox_processed_event 
 *         FOREIGN KEY (idempotency_key) REFERENCES processed_events(idempotency_key)
 * );
 * 
 * CREATE INDEX idx_outbox_status ON outbox_entries(status);
 * CREATE INDEX idx_outbox_next_retry ON outbox_entries(next_retry_at);
 * CREATE INDEX idx_outbox_idempotency_key ON outbox_entries(idempotency_key);
 * CREATE INDEX idx_outbox_lock_expires ON outbox_entries(lock_expires_at);
 * </pre>
 */
@Entity
@Table(name = "outbox_entries", indexes = {
        @Index(name = "idx_outbox_status", columnList = "status"),
        @Index(name = "idx_outbox_next_retry", columnList = "next_retry_at"),
        @Index(name = "idx_outbox_idempotency_key", columnList = "idempotency_key"),
        @Index(name = "idx_outbox_lock_expires", columnList = "lock_expires_at")
})
public class OutboxEntryEntity {
    
    @Id
    @Column(name = "id", length = 36, nullable = false)
    private String id;
    
    @Column(name = "idempotency_key", length = 255, nullable = false)
    private String idempotencyKey;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 50, nullable = false)
    private OutboxStatus status;
    
    @Column(name = "destination", length = 255, nullable = false)
    private String destination;
    
    @Column(name = "http_method", length = 10)
    private String httpMethod;
    
    @Column(name = "endpoint", length = 1000)
    private String endpoint;
    
    @Lob
    @Column(name = "payload", nullable = false)
    private String payload;
    
    @Lob
    @Column(name = "headers")
    private String headers; // JSON serialized
    
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    
    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;
    
    @Column(name = "delivered_at")
    private Instant deliveredAt;
    
    @Column(name = "next_retry_at")
    private Instant nextRetryAt;
    
    @Column(name = "retry_count")
    private int retryCount = 0;
    
    @Column(name = "last_error", length = 2000)
    private String lastError;
    
    @Column(name = "last_http_status")
    private Integer lastHttpStatus;
    
    @Column(name = "locked_by", length = 255)
    private String lockedBy;
    
    @Column(name = "lock_expires_at")
    private Instant lockExpiresAt;
    
    @Version
    @Column(name = "version")
    private Long version;
    
    // Default constructor for JPA
    public OutboxEntryEntity() {
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
    
    public String getHeaders() {
        return headers;
    }
    
    public void setHeaders(String headers) {
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
}
