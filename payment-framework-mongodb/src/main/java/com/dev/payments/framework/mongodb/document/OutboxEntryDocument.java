package com.dev.payments.framework.mongodb.document;

import com.dev.payments.framework.model.OutboxEntry.OutboxStatus;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

/**
 * MongoDB document for OutboxEntry.
 * 
 * Collection: outbox_entries
 */
@Document(collection = "outbox_entries")
@CompoundIndexes({
    @CompoundIndex(name = "idx_status_nextRetry", def = "{'status': 1, 'nextRetryAt': 1}"),
    @CompoundIndex(name = "idx_status_createdAt", def = "{'status': 1, 'createdAt': 1}")
})
public class OutboxEntryDocument {
    
    @Id
    private String id;
    
    @Indexed
    private String idempotencyKey;
    
    @Indexed
    private OutboxStatus status;
    
    @Indexed
    private String destination;
    
    private String httpMethod;
    
    private String endpoint;
    
    private String payload;
    
    private Map<String, String> headers;
    
    @Indexed
    private Instant createdAt;
    
    private Instant lastAttemptAt;
    
    private Instant deliveredAt;
    
    @Indexed
    private Instant nextRetryAt;
    
    private int retryCount;
    
    private String lastError;
    
    private Integer lastHttpStatus;
    
    private String lockedBy;
    
    @Indexed
    private Instant lockExpiresAt;
    
    @Version
    private Long version;
    
    // Default constructor
    public OutboxEntryDocument() {
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
}
