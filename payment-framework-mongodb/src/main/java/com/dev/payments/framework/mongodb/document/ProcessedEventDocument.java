package com.dev.payments.framework.mongodb.document;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

/**
 * MongoDB document for ProcessedEvent.
 *
 * The idempotency key doubles as the MongoDB _id, giving us a free unique index
 * and making duplicate-key inserts fail atomically — exactly the idempotency guarantee we need.
 *
 * Collection: processed_events
 * Indexes:
 *   - _id          (implicit, on idempotencyKey)
 *   - state        (for findByState / findStuckProcessing queries)
 *   - received_at  (for findStuckProcessing / cleanup queries)
 */
@Document(collection = "processed_events")
public class ProcessedEventDocument {

    @Id
    private String idempotencyKey;

    @Indexed
    @Field("state")
    private String state;

    @Field("source")
    private String source;

    @Field("destination")
    private String destination;

    @Indexed
    @Field("received_at")
    private Instant receivedAt;

    @Field("completed_at")
    private Instant completedAt;

    @Field("processed_by")
    private String processedBy;

    @Field("retry_count")
    private int retryCount = 0;

    @Field("last_error")
    private String lastError;

    @Version
    @Field("version")
    private Long version;

    public ProcessedEventDocument() {
    }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getDestination() { return destination; }
    public void setDestination(String destination) { this.destination = destination; }

    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant receivedAt) { this.receivedAt = receivedAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }

    public String getProcessedBy() { return processedBy; }
    public void setProcessedBy(String processedBy) { this.processedBy = processedBy; }

    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }

    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
