package com.dev.payments.framework.mongodb.repository;

import com.dev.payments.framework.model.OutboxEntry;
import com.dev.payments.framework.model.OutboxEntry.OutboxStatus;
import com.dev.payments.framework.mongodb.document.OutboxEntryDocument;
import com.dev.payments.framework.repository.OutboxRepository;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * MongoDB implementation of OutboxRepository.
 *
 * Critical invariants:
 *
 * tryLock() — ATOMIC CAS via findAndModify.
 *   The filter requires lockedBy IS NULL or lockExpiresAt IS past, so two concurrent
 *   callers can never both succeed: MongoDB processes findAndModify under a document-level
 *   write lock, and the winner's update immediately makes the filter false for the loser.
 *
 * releaseLock() — ownership-validated.
 *   The WHERE clause includes AND lockedBy = :lockedBy so a crashed processor
 *   whose lock was already stolen by another node cannot accidentally clear the new lock.
 *
 * findReadyForProcessing() — FIFO.
 *   Returns PENDING entries plus RETRY_PENDING entries whose nextRetryAt is in the past,
 *   ordered by createdAt ASC to guarantee fairness.
 */
@Component
public class MongoOutboxRepository implements OutboxRepository {

    private final OutboxEntryMongoStore store;
    private final MongoTemplate mongoTemplate;

    public MongoOutboxRepository(OutboxEntryMongoStore store, MongoTemplate mongoTemplate) {
        this.store = store;
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public Optional<OutboxEntry> findById(String id) {
        return store.findById(id).map(this::toModel);
    }

    @Override
    public List<OutboxEntry> findByIdempotencyKey(String idempotencyKey) {
        return store.findByIdempotencyKey(idempotencyKey).stream()
                .map(this::toModel)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public OutboxEntry save(OutboxEntry entry) {
        OutboxEntryDocument saved = store.save(toDocument(entry));
        return toModel(saved);
    }

    @Override
    public List<OutboxEntry> findReadyForProcessing(int limit) {
        Instant now = Instant.now();
        Criteria ready = new Criteria().orOperator(
                Criteria.where("status").is(OutboxStatus.PENDING.name()),
                Criteria.where("status").is(OutboxStatus.RETRY_PENDING.name())
                        .and("next_retry_at").lte(now));
        Query query = Query.query(ready)
                .with(Sort.by(Sort.Direction.ASC, "created_at"))
                .limit(limit);
        return mongoTemplate.find(query, OutboxEntryDocument.class).stream()
                .map(this::toModel)
                .collect(Collectors.toList());
    }

    /**
     * Atomically acquires a processing lock on an outbox entry.
     *
     * The filter succeeds only when the entry is currently unlocked (lockedBy is null)
     * OR the previous lock has expired. MongoDB's findAndModify executes this as a
     * single atomic operation — no read-then-write race condition is possible.
     */
    @Override
    @Transactional
    public boolean tryLock(String entryId, String lockedBy, int lockDurationSeconds) {
        Instant now = Instant.now();
        Criteria unlocked = new Criteria().orOperator(
                Criteria.where("locked_by").exists(false),
                Criteria.where("locked_by").is(null),
                Criteria.where("lock_expires_at").lt(now));
        Query query = Query.query(Criteria.where("_id").is(entryId).andOperator(unlocked));
        Update update = new Update()
                .set("locked_by", lockedBy)
                .set("lock_expires_at", now.plusSeconds(lockDurationSeconds))
                .set("status", OutboxStatus.PROCESSING.name());
        OutboxEntryDocument result = mongoTemplate.findAndModify(
                query, update,
                FindAndModifyOptions.options().returnNew(true),
                OutboxEntryDocument.class);
        return result != null;
    }

    /**
     * Releases a lock only if the caller owns it.
     *
     * The WHERE clause includes lockedBy = :lockedBy so a node whose lock was
     * already stolen by another (after expiry) cannot accidentally clear the new lock.
     */
    @Override
    @Transactional
    public boolean releaseLock(String entryId, String lockedBy) {
        Query query = Query.query(
                Criteria.where("_id").is(entryId)
                        .and("locked_by").is(lockedBy));
        Update update = new Update()
                .unset("locked_by")
                .unset("lock_expires_at");
        return mongoTemplate.updateFirst(query, update, OutboxEntryDocument.class).getModifiedCount() > 0;
    }

    @Override
    public List<OutboxEntry> findExpiredLocks() {
        Query query = Query.query(
                Criteria.where("locked_by").exists(true).ne(null)
                        .and("lock_expires_at").lt(Instant.now()));
        return mongoTemplate.find(query, OutboxEntryDocument.class).stream()
                .map(this::toModel)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public boolean markDelivered(String entryId) {
        Instant now = Instant.now();
        Query query = Query.query(Criteria.where("_id").is(entryId));
        Update update = new Update()
                .set("status", OutboxStatus.DELIVERED.name())
                .set("delivered_at", now)
                .set("last_attempt_at", now)
                .unset("locked_by")
                .unset("lock_expires_at");
        return mongoTemplate.updateFirst(query, update, OutboxEntryDocument.class).getModifiedCount() > 0;
    }

    @Override
    @Transactional
    public boolean recordFailure(String entryId, String error, Integer httpStatus, Instant nextRetryAt) {
        Query query = Query.query(Criteria.where("_id").is(entryId));
        Update update = new Update()
                .set("last_error", error)
                .set("last_http_status", httpStatus)
                .set("last_attempt_at", Instant.now())
                .set("next_retry_at", nextRetryAt)
                .set("status", nextRetryAt != null
                        ? OutboxStatus.RETRY_PENDING.name()
                        : OutboxStatus.FAILED.name())
                .unset("locked_by")
                .unset("lock_expires_at")
                .inc("retry_count", 1);
        return mongoTemplate.updateFirst(query, update, OutboxEntryDocument.class).getModifiedCount() > 0;
    }

    @Override
    @Transactional
    public boolean markFailed(String entryId, String error) {
        Query query = Query.query(Criteria.where("_id").is(entryId));
        Update update = new Update()
                .set("status", OutboxStatus.FAILED.name())
                .set("last_error", error)
                .set("last_attempt_at", Instant.now())
                .unset("locked_by")
                .unset("lock_expires_at");
        return mongoTemplate.updateFirst(query, update, OutboxEntryDocument.class).getModifiedCount() > 0;
    }

    @Override
    public List<OutboxEntry> findByStatus(OutboxStatus status, int limit) {
        return store.findByStatus(status.name(), Sort.by(Sort.Direction.ASC, "created_at"))
                .stream()
                .limit(limit)
                .map(this::toModel)
                .collect(Collectors.toList());
    }

    @Override
    public long countByStatus(OutboxStatus status) {
        return store.countByStatus(status.name());
    }

    @Override
    public long countByDestination(String destination) {
        return store.countByDestination(destination);
    }

    @Override
    @Transactional
    public int deleteDeliveredBefore(Instant olderThan) {
        Query query = Query.query(
                Criteria.where("status").is(OutboxStatus.DELIVERED.name())
                        .and("delivered_at").lt(olderThan));
        return (int) mongoTemplate.remove(query, OutboxEntryDocument.class).getDeletedCount();
    }

    @Override
    public double getAverageDeliveryTimeMillis(Instant since) {
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(
                        Criteria.where("status").is(OutboxStatus.DELIVERED.name())
                                .and("delivered_at").gte(since)
                                .and("created_at").exists(true)),
                Aggregation.project()
                        .andExpression("subtract(delivered_at, created_at)").as("deliveryMs"),
                Aggregation.group().avg("deliveryMs").as("avgMs"));

        AggregationResults<Map> results = mongoTemplate.aggregate(
                aggregation, OutboxEntryDocument.class, Map.class);
        Map uniqueMappedResult = results.getUniqueMappedResult();
        if (uniqueMappedResult == null) {
            return 0.0;
        }
        Number avg = (Number) uniqueMappedResult.get("avgMs");
        return avg != null ? avg.doubleValue() : 0.0;
    }

    // -------------------------------------------------------------------------
    // Converters
    // -------------------------------------------------------------------------

    private OutboxEntry toModel(OutboxEntryDocument doc) {
        return OutboxEntry.builder()
                .id(doc.getId())
                .idempotencyKey(doc.getIdempotencyKey())
                .status(OutboxStatus.valueOf(doc.getStatus()))
                .destination(doc.getDestination())
                .httpMethod(doc.getHttpMethod())
                .endpoint(doc.getEndpoint())
                .payload(doc.getPayload())
                .headers(doc.getHeaders())
                .createdAt(doc.getCreatedAt())
                .build();
    }

    private OutboxEntryDocument toDocument(OutboxEntry model) {
        OutboxEntryDocument doc = new OutboxEntryDocument();
        doc.setId(model.getId());
        doc.setIdempotencyKey(model.getIdempotencyKey());
        doc.setStatus(model.getStatus().name());
        doc.setDestination(model.getDestination());
        doc.setHttpMethod(model.getHttpMethod());
        doc.setEndpoint(model.getEndpoint());
        doc.setPayload(model.getPayload());
        doc.setHeaders(model.getHeaders());
        doc.setCreatedAt(model.getCreatedAt());
        doc.setLastAttemptAt(model.getLastAttemptAt());
        doc.setDeliveredAt(model.getDeliveredAt());
        doc.setNextRetryAt(model.getNextRetryAt());
        doc.setRetryCount(model.getRetryCount());
        doc.setLastError(model.getLastError());
        doc.setLastHttpStatus(model.getLastHttpStatus());
        doc.setLockedBy(model.getLockedBy());
        doc.setLockExpiresAt(model.getLockExpiresAt());
        return doc;
    }
}
