package com.dev.payments.framework.mongodb.repository;

import com.dev.payments.framework.model.ProcessedEvent;
import com.dev.payments.framework.mongodb.document.ProcessedEventDocument;
import com.dev.payments.framework.repository.ProcessedEventRepository;
import com.dev.payments.framework.state.PaymentState;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * MongoDB implementation of ProcessedEventRepository.
 */
@Repository
public class MongoProcessedEventRepository implements ProcessedEventRepository {
    
    private final MongoTemplate mongoTemplate;
    
    public MongoProcessedEventRepository(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }
    
    @Override
    public Optional<ProcessedEvent> findByIdempotencyKey(String idempotencyKey) {
        ProcessedEventDocument doc = mongoTemplate.findById(idempotencyKey, ProcessedEventDocument.class);
        return Optional.ofNullable(doc).map(this::toModel);
    }
    
    @Override
    public boolean existsByIdempotencyKey(String idempotencyKey) {
        Query query = Query.query(Criteria.where("_id").is(idempotencyKey));
        return mongoTemplate.exists(query, ProcessedEventDocument.class);
    }
    
    @Override
    public ProcessedEvent save(ProcessedEvent event) {
        ProcessedEventDocument doc = toDocument(event);
        doc = mongoTemplate.save(doc);
        return toModel(doc);
    }
    
    @Override
    public boolean updateState(String idempotencyKey, PaymentState newState, String lastError) {
        Query query = Query.query(Criteria.where("_id").is(idempotencyKey));
        Update update = new Update()
                .set("state", newState)
                .set("lastError", lastError);
        
        var result = mongoTemplate.updateFirst(query, update, ProcessedEventDocument.class);
        return result.getModifiedCount() > 0;
    }
    
    @Override
    public boolean markCompleted(String idempotencyKey) {
        Query query = Query.query(Criteria.where("_id").is(idempotencyKey));
        Update update = new Update()
                .set("state", PaymentState.COMPLETED)
                .set("completedAt", Instant.now());
        
        var result = mongoTemplate.updateFirst(query, update, ProcessedEventDocument.class);
        return result.getModifiedCount() > 0;
    }
    
    @Override
    public List<ProcessedEvent> findByState(PaymentState state) {
        Query query = Query.query(Criteria.where("state").is(state));
        return mongoTemplate.find(query, ProcessedEventDocument.class).stream()
                .map(this::toModel)
                .collect(Collectors.toList());
    }
    
    @Override
    public List<ProcessedEvent> findStuckProcessing(Instant olderThan) {
        Query query = Query.query(
                Criteria.where("state").is(PaymentState.PROCESSING)
                        .and("receivedAt").lt(olderThan)
        );
        return mongoTemplate.find(query, ProcessedEventDocument.class).stream()
                .map(this::toModel)
                .collect(Collectors.toList());
    }
    
    @Override
    public List<ProcessedEvent> findRecentFailures(int limit) {
        Query query = Query.query(Criteria.where("state").is(PaymentState.FAILED))
                .limit(limit);
        return mongoTemplate.find(query, ProcessedEventDocument.class).stream()
                .map(this::toModel)
                .collect(Collectors.toList());
    }
    
    @Override
    public long countByState(PaymentState state) {
        Query query = Query.query(Criteria.where("state").is(state));
        return mongoTemplate.count(query, ProcessedEventDocument.class);
    }
    
    @Override
    public int deleteCompletedBefore(Instant olderThan) {
        Query query = Query.query(
                Criteria.where("state").is(PaymentState.COMPLETED)
                        .and("completedAt").lt(olderThan)
        );
        var result = mongoTemplate.remove(query, ProcessedEventDocument.class);
        return (int) result.getDeletedCount();
    }
    
    // Document <-> Model conversion
    
    private ProcessedEvent toModel(ProcessedEventDocument doc) {
        return ProcessedEvent.builder()
                .idempotencyKey(doc.getIdempotencyKey())
                .state(doc.getState())
                .source(doc.getSource())
                .destination(doc.getDestination())
                .receivedAt(doc.getReceivedAt())
                .completedAt(doc.getCompletedAt())
                .processedBy(doc.getProcessedBy())
                .retryCount(doc.getRetryCount())
                .lastError(doc.getLastError())
                .build();
    }
    
    private ProcessedEventDocument toDocument(ProcessedEvent model) {
        ProcessedEventDocument doc = new ProcessedEventDocument();
        doc.setIdempotencyKey(model.getIdempotencyKey());
        doc.setState(model.getState());
        doc.setSource(model.getSource());
        doc.setDestination(model.getDestination());
        doc.setReceivedAt(model.getReceivedAt());
        doc.setCompletedAt(model.getCompletedAt());
        doc.setProcessedBy(model.getProcessedBy());
        doc.setRetryCount(model.getRetryCount());
        doc.setLastError(model.getLastError());
        return doc;
    }
}
