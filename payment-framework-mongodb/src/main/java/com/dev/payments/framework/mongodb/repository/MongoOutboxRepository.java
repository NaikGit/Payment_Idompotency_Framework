package com.dev.payments.framework.mongodb.repository;

import com.dev.payments.framework.model.OutboxEntry;
import com.dev.payments.framework.model.OutboxEntry.OutboxStatus;
import com.dev.payments.framework.mongodb.document.OutboxEntryDocument;
import com.dev.payments.framework.repository.OutboxRepository;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * MongoDB implementation of OutboxRepository.
 */
@Repository
public class MongoOutboxRepository implements OutboxRepository {
    
    private final MongoTemplate mongoTemplate;
    
    public MongoOutboxRepository(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }
    
    @Override
    public Optional<OutboxEntry> findById(String id) {
        OutboxEntryDocument doc = mongoTemplate.findById(id, OutboxEntryDocument.class);
        return Optional.ofNullable(doc).map(this::toModel);
    }
    
    @Override
    public List<OutboxEntry> findByIdempotencyKey(String idempotencyKey) {
        Query query = Query.query(Criteria.where("idempotencyKey").is(idempotencyKey));
        return mongoTemplate.find(query, OutboxEntryDocument.class).stream()
                .map(this::toModel)
                .collect(Collectors.toList());
    }
    
    @Override
    public OutboxEntry save(OutboxEntry entry) {
        OutboxEntryDocument doc = toDocument(entry);
        if (doc.getId() == null) {
            doc.setId(UUID.randomUUID().toString());
        }
        doc = mongoTemplate.save(doc);
        return toModel(doc);
    }
    
    @Override
    public List<OutboxEntry> findReadyForProcessing(int limit) {
        Instant now = Instant.now();
        
        // Find PENDING or RETRY_PENDING (with nextRetryAt in the past)
        Criteria criteria = new Criteria().orOperator(
                Criteria.where("status").is(OutboxStatus.PENDING),
                Criteria.where("status").is(OutboxStatus.RETRY_PENDING)
                        .and("nextRetryAt").lte(now)
        );
        
        // Exclude locked entries
        criteria = criteria.andOperator(
                new Criteria().orOperator(
                        Criteria.where("lockedBy").is(null),
                        Criteria.where("lockExpiresAt").lte(now)
                )
        );
        
        Query query = Query.query(criteria)
                .with(Sort.by(Sort.Direction.ASC, "createdAt"))
                .limit(limit);
        
        return mongoTemplate.find(query, OutboxEntryDocument.class).stream()
                .map(this::toModel)
                .collect(Collectors.toList());
    }
    
    @Override
    public boolean tryLock(String entryId, String lockedBy, int lockDurationSeconds) {
        Instant now = Instant.now();
        Instant lockExpires = now.plusSeconds(lockDurationSeconds);
        
        // Only lock if not already locked (or lock expired)
        Query query = Query.query(
                Criteria.where("_id").is(entryId)
                        .andOperator(
                                new Criteria().orOperator(
                                        Criteria.where("lockedBy").is(null),
                                        Criteria.where("lockExpiresAt").lte(now)
                                )
                        )
        );
        
        Update update = new Update()
                .set("lockedBy", lockedBy)
                .set("lockExpiresAt", lockExpires)
                .set("status", OutboxStatus.PROCESSING);
        
        var result = mongoTemplate.updateFirst(query, update, OutboxEntryDocument.class);
        return result.getModifiedCount() > 0;
    }
    
    @Override
    public boolean releaseLock(String entryId, String lockedBy) {
        Query query = Query.query(
                Criteria.where("_id").is(entryId)
                        .and("lockedBy").is(lockedBy)
        );
        
        Update update = new Update()
                .unset("lockedBy")
                .unset("lockExpiresAt");
        
        var result = mongoTemplate.updateFirst(query, update, OutboxEntryDocument.class);
        return result.getModifiedCount() > 0;
    }
    
    @Override
    public List<OutboxEntry> findExpiredLocks() {
        Query query = Query.query(
                Criteria.where("lockedBy").ne(null)
                        .and("lockExpiresAt").lte(Instant.now())
        );
        
        return mongoTemplate.find(query, OutboxEntryDocument.class).stream()
                .map(this::toModel)
                .collect(Collectors.toList());
    }
    
    @Override
    public boolean markDelivered(String entryId) {
        Query query = Query.query(Criteria.where("_id").is(entryId));
        Update update = new Update()
                .set("status", OutboxStatus.DELIVERED)
                .set("deliveredAt", Instant.now())
                .set("lastAttemptAt", Instant.now())
                .unset("lockedBy")
                .unset("lockExpiresAt");
        
        var result = mongoTemplate.updateFirst(query, update, OutboxEntryDocument.class);
        return result.getModifiedCount() > 0;
    }
    
    @Override
    public boolean recordFailure(String entryId, String error, Integer httpStatus, Instant nextRetryAt) {
        Query query = Query.query(Criteria.where("_id").is(entryId));
        Update update = new Update()
                .set("status", OutboxStatus.RETRY_PENDING)
                .set("lastError", error)
                .set("lastHttpStatus", httpStatus)
                .set("lastAttemptAt", Instant.now())
                .set("nextRetryAt", nextRetryAt)
                .inc("retryCount", 1)
                .unset("lockedBy")
                .unset("lockExpiresAt");
        
        var result = mongoTemplate.updateFirst(query, update, OutboxEntryDocument.class);
        return result.getModifiedCount() > 0;
    }
    
    @Override
    public boolean markFailed(String entryId, String error) {
        Query query = Query.query(Criteria.where("_id").is(entryId));
        Update update = new Update()
                .set("status", OutboxStatus.FAILED)
                .set("lastError", error)
                .set("lastAttemptAt", Instant.now())
                .unset("lockedBy")
                .unset("lockExpiresAt");
        
        var result = mongoTemplate.updateFirst(query, update, OutboxEntryDocument.class);
        return result.getModifiedCount() > 0;
    }
    
    @Override
    public List<OutboxEntry> findByStatus(OutboxStatus status, int limit) {
        Query query = Query.query(Criteria.where("status").is(status))
                .limit(limit);
        
        return mongoTemplate.find(query, OutboxEntryDocument.class).stream()
                .map(this::toModel)
                .collect(Collectors.toList());
    }
    
    @Override
    public long countByStatus(OutboxStatus status) {
        Query query = Query.query(Criteria.where("status").is(status));
        return mongoTemplate.count(query, OutboxEntryDocument.class);
    }
    
    @Override
    public long countByDestination(String destination) {
        Query query = Query.query(Criteria.where("destination").is(destination));
        return mongoTemplate.count(query, OutboxEntryDocument.class);
    }
    
    @Override
    public int deleteDeliveredBefore(Instant olderThan) {
        Query query = Query.query(
                Criteria.where("status").is(OutboxStatus.DELIVERED)
                        .and("deliveredAt").lt(olderThan)
        );
        
        var result = mongoTemplate.remove(query, OutboxEntryDocument.class);
        return (int) result.getDeletedCount();
    }
    
    @Override
    public double getAverageDeliveryTimeMillis(Instant since) {
        // Simplified implementation - in production, use aggregation pipeline
        Query query = Query.query(
                Criteria.where("status").is(OutboxStatus.DELIVERED)
                        .and("deliveredAt").gte(since)
        );
        
        List<OutboxEntryDocument> docs = mongoTemplate.find(query, OutboxEntryDocument.class);
        
        if (docs.isEmpty()) {
            return 0;
        }
        
        double totalMillis = docs.stream()
                .filter(d -> d.getCreatedAt() != null && d.getDeliveredAt() != null)
                .mapToLong(d -> d.getDeliveredAt().toEpochMilli() - d.getCreatedAt().toEpochMilli())
                .sum();
        
        return totalMillis / docs.size();
    }
    
    // Document <-> Model conversion
    
    private OutboxEntry toModel(OutboxEntryDocument doc) {
        OutboxEntry entry = new OutboxEntry();
        entry.setId(doc.getId());
        entry.setIdempotencyKey(doc.getIdempotencyKey());
        entry.setStatus(doc.getStatus());
        entry.setDestination(doc.getDestination());
        entry.setHttpMethod(doc.getHttpMethod());
        entry.setEndpoint(doc.getEndpoint());
        entry.setPayload(doc.getPayload());
        entry.setHeaders(doc.getHeaders());
        entry.setCreatedAt(doc.getCreatedAt());
        entry.setLastAttemptAt(doc.getLastAttemptAt());
        entry.setDeliveredAt(doc.getDeliveredAt());
        entry.setNextRetryAt(doc.getNextRetryAt());
        entry.setRetryCount(doc.getRetryCount());
        entry.setLastError(doc.getLastError());
        entry.setLastHttpStatus(doc.getLastHttpStatus());
        entry.setLockedBy(doc.getLockedBy());
        entry.setLockExpiresAt(doc.getLockExpiresAt());
        entry.setVersion(doc.getVersion());
        return entry;
    }
    
    private OutboxEntryDocument toDocument(OutboxEntry model) {
        OutboxEntryDocument doc = new OutboxEntryDocument();
        doc.setId(model.getId());
        doc.setIdempotencyKey(model.getIdempotencyKey());
        doc.setStatus(model.getStatus());
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
        doc.setVersion(model.getVersion());
        return doc;
    }
}
