package com.dev.payments.framework.mongodb.repository;

import com.dev.payments.framework.model.ProcessedEvent;
import com.dev.payments.framework.mongodb.document.ProcessedEventDocument;
import com.dev.payments.framework.repository.ProcessedEventRepository;
import com.dev.payments.framework.state.PaymentState;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * MongoDB implementation of ProcessedEventRepository.
 *
 * Read operations use the Spring Data derived-query store.
 * Mutating operations use MongoTemplate for atomic single-document updates —
 * no read-then-write patterns that could leave the collection in a partial state.
 */
@Component
public class MongoProcessedEventRepository implements ProcessedEventRepository {

    private final ProcessedEventMongoStore store;
    private final MongoTemplate mongoTemplate;

    public MongoProcessedEventRepository(ProcessedEventMongoStore store, MongoTemplate mongoTemplate) {
        this.store = store;
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public Optional<ProcessedEvent> findByIdempotencyKey(String idempotencyKey) {
        return store.findById(idempotencyKey).map(this::toModel);
    }

    @Override
    public boolean existsByIdempotencyKey(String idempotencyKey) {
        return store.existsByIdempotencyKey(idempotencyKey);
    }

    @Override
    @Transactional
    public ProcessedEvent save(ProcessedEvent event) {
        ProcessedEventDocument saved = store.save(toDocument(event));
        return toModel(saved);
    }

    @Override
    @Transactional
    public boolean updateState(String idempotencyKey, PaymentState newState, String lastError) {
        Query query = Query.query(Criteria.where("_id").is(idempotencyKey));
        Update update = new Update()
                .set("state", newState.name())
                .set("last_error", lastError);
        return mongoTemplate.updateFirst(query, update, ProcessedEventDocument.class).getModifiedCount() > 0;
    }

    @Override
    @Transactional
    public boolean markCompleted(String idempotencyKey) {
        Query query = Query.query(Criteria.where("_id").is(idempotencyKey));
        Update update = new Update()
                .set("state", PaymentState.COMPLETED.name())
                .set("completed_at", Instant.now());
        return mongoTemplate.updateFirst(query, update, ProcessedEventDocument.class).getModifiedCount() > 0;
    }

    @Override
    public List<ProcessedEvent> findByState(PaymentState state) {
        return store.findByState(state.name()).stream()
                .map(this::toModel)
                .collect(Collectors.toList());
    }

    @Override
    public List<ProcessedEvent> findStuckProcessing(Instant olderThan) {
        Query query = Query.query(
                Criteria.where("state").is(PaymentState.PROCESSING.name())
                        .and("received_at").lt(olderThan));
        return mongoTemplate.find(query, ProcessedEventDocument.class).stream()
                .map(this::toModel)
                .collect(Collectors.toList());
    }

    @Override
    public List<ProcessedEvent> findRecentFailures(int limit) {
        Query query = Query.query(Criteria.where("state").is(PaymentState.FAILED.name()))
                .with(Sort.by(Sort.Direction.DESC, "received_at"))
                .limit(limit);
        return mongoTemplate.find(query, ProcessedEventDocument.class).stream()
                .map(this::toModel)
                .collect(Collectors.toList());
    }

    @Override
    public long countByState(PaymentState state) {
        return store.countByState(state.name());
    }

    @Override
    @Transactional
    public int deleteCompletedBefore(Instant olderThan) {
        Query query = Query.query(
                Criteria.where("state").is(PaymentState.COMPLETED.name())
                        .and("completed_at").lt(olderThan));
        return (int) mongoTemplate.remove(query, ProcessedEventDocument.class).getDeletedCount();
    }

    // -------------------------------------------------------------------------
    // Converters
    // -------------------------------------------------------------------------

    private ProcessedEvent toModel(ProcessedEventDocument doc) {
        return ProcessedEvent.builder()
                .idempotencyKey(doc.getIdempotencyKey())
                .state(PaymentState.valueOf(doc.getState()))
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
        doc.setState(model.getState().name());
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
