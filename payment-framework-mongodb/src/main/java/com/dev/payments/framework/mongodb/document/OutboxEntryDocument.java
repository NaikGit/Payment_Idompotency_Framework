package com.dev.payments.framework.mongodb.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * MongoDB document for OutboxEntry.
 *
 * Collection: outbox_entries
 * Indexes:
 *   - status             (for findReadyForProcessing / findByStatus queries)
 *   - next_retry_at      (for findReadyForProcessing with RETRY_PENDING)
 *   - idempotency_key    (for findByIdempotencyKey)
 *   - lock_expires_at    (for findExpiredLocks)
 *
 * tryLock() is implemented via MongoTemplate.findAndModify() — a single atomic
 * compare-and-swap: only acquires the lock if lockedBy IS NULL or lockExpiresAt IS past.
 */
@Document(collection = "outbox_entries")
public class OutboxEntryDocument {

    @Id
    private String id;

    @Indexed
    @Field("idempotency_key")
    private String idempotencyKey;

    @Indexed
    @Field("status")
    private String status;

    @Field("destination")
    private String destination;

    @Field("http_method")
    private String httpMethod;

    @Field("endpoint")
    private String endpoint;

    @Field("payload")
    private String payload;

    @Field("headers")
    private Map<String, String> headers = new HashMap<>();

    @Field("created_at")
    private Instant createdAt;

    @Field("last_attempt_at")
    private Instant lastAttemptAt;

    @Field("delivered_at")
    private Instant deliveredAt;

    @Indexed
    @Field("next_retry_at")
    private Instant nextRetryAt;

    @Field("retry_count")
    private int retryCount = 0;

    @Field("last_error")
    private String lastError;

    @Field("last_http_status")
    private Integer lastHttpStatus;

    @Field("locked_by")
    private String lockedBy;

    @Indexed
    @Field("lock_expires_at")
    private Instant lockExpiresAt;

    @Version
    @Field("version")
    private Long version;

    public OutboxEntryDocument() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getDestination() { return destination; }
    public void setDestination(String destination) { this.destination = destination; }

    public String getHttpMethod() { return httpMethod; }
    public void setHttpMethod(String httpMethod) { this.httpMethod = httpMethod; }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }

    public Map<String, String> getHeaders() { return headers; }
    public void setHeaders(Map<String, String> headers) { this.headers = headers; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getLastAttemptAt() { return lastAttemptAt; }
    public void setLastAttemptAt(Instant lastAttemptAt) { this.lastAttemptAt = lastAttemptAt; }

    public Instant getDeliveredAt() { return deliveredAt; }
    public void setDeliveredAt(Instant deliveredAt) { this.deliveredAt = deliveredAt; }

    public Instant getNextRetryAt() { return nextRetryAt; }
    public void setNextRetryAt(Instant nextRetryAt) { this.nextRetryAt = nextRetryAt; }

    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }

    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }

    public Integer getLastHttpStatus() { return lastHttpStatus; }
    public void setLastHttpStatus(Integer lastHttpStatus) { this.lastHttpStatus = lastHttpStatus; }

    public String getLockedBy() { return lockedBy; }
    public void setLockedBy(String lockedBy) { this.lockedBy = lockedBy; }

    public Instant getLockExpiresAt() { return lockExpiresAt; }
    public void setLockExpiresAt(Instant lockExpiresAt) { this.lockExpiresAt = lockExpiresAt; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
