package com.dev.payments.framework.jpa.repository;

import com.dev.payments.framework.jpa.entity.ProcessedEventEntity;
import com.dev.payments.framework.model.ProcessedEvent;
import com.dev.payments.framework.repository.ProcessedEventRepository;
import com.dev.payments.framework.state.PaymentState;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * JPA implementation of ProcessedEventRepository.
 * Adapts between the domain model and JPA entities.
 */
@Component
public class JpaProcessedEventRepository implements ProcessedEventRepository {
    
    private final ProcessedEventJpaRepository jpaRepository;
    
    public JpaProcessedEventRepository(ProcessedEventJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }
    
    @Override
    public Optional<ProcessedEvent> findByIdempotencyKey(String idempotencyKey) {
        return jpaRepository.findById(idempotencyKey)
                .map(this::toModel);
    }
    
    @Override
    public boolean existsByIdempotencyKey(String idempotencyKey) {
        return jpaRepository.existsByIdempotencyKey(idempotencyKey);
    }
    
    @Override
    @Transactional
    public ProcessedEvent save(ProcessedEvent event) {
        ProcessedEventEntity entity = toEntity(event);
        entity = jpaRepository.save(entity);
        return toModel(entity);
    }
    
    @Override
    @Transactional
    public boolean updateState(String idempotencyKey, PaymentState newState, String lastError) {
        return jpaRepository.updateState(idempotencyKey, newState, lastError) > 0;
    }
    
    @Override
    @Transactional
    public boolean markCompleted(String idempotencyKey) {
        return jpaRepository.markCompleted(idempotencyKey, Instant.now()) > 0;
    }
    
    @Override
    public List<ProcessedEvent> findByState(PaymentState state) {
        return jpaRepository.findByState(state).stream()
                .map(this::toModel)
                .collect(Collectors.toList());
    }
    
    @Override
    public List<ProcessedEvent> findStuckProcessing(Instant olderThan) {
        return jpaRepository.findStuckProcessing(olderThan).stream()
                .map(this::toModel)
                .collect(Collectors.toList());
    }
    
    @Override
    public List<ProcessedEvent> findRecentFailures(int limit) {
        return jpaRepository.findRecentFailures(PageRequest.of(0, limit)).stream()
                .map(this::toModel)
                .collect(Collectors.toList());
    }
    
    @Override
    public long countByState(PaymentState state) {
        return jpaRepository.countByState(state);
    }
    
    @Override
    @Transactional
    public int deleteCompletedBefore(Instant olderThan) {
        return jpaRepository.deleteCompletedBefore(olderThan);
    }
    
    // Entity <-> Model conversion
    
    private ProcessedEvent toModel(ProcessedEventEntity entity) {
        return ProcessedEvent.builder()
                .idempotencyKey(entity.getIdempotencyKey())
                .state(entity.getState())
                .source(entity.getSource())
                .destination(entity.getDestination())
                .receivedAt(entity.getReceivedAt())
                .completedAt(entity.getCompletedAt())
                .processedBy(entity.getProcessedBy())
                .retryCount(entity.getRetryCount())
                .lastError(entity.getLastError())
                .build();
    }
    
    private ProcessedEventEntity toEntity(ProcessedEvent model) {
        ProcessedEventEntity entity = new ProcessedEventEntity();
        entity.setIdempotencyKey(model.getIdempotencyKey());
        entity.setState(model.getState());
        entity.setSource(model.getSource());
        entity.setDestination(model.getDestination());
        entity.setReceivedAt(model.getReceivedAt());
        entity.setCompletedAt(model.getCompletedAt());
        entity.setProcessedBy(model.getProcessedBy());
        entity.setRetryCount(model.getRetryCount());
        entity.setLastError(model.getLastError());
        return entity;
    }
}
