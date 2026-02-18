package com.dev.payments.framework.jpa.repository;

import com.dev.payments.framework.jpa.entity.OutboxEntryEntity;
import com.dev.payments.framework.model.OutboxEntry;
import com.dev.payments.framework.model.OutboxEntry.OutboxStatus;
import com.dev.payments.framework.repository.OutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * JPA implementation of OutboxRepository.
 */
@Component
public class JpaOutboxRepository implements OutboxRepository {
    
    private final OutboxEntryJpaRepository jpaRepository;
    private final ObjectMapper objectMapper;
    
    public JpaOutboxRepository(OutboxEntryJpaRepository jpaRepository, ObjectMapper objectMapper) {
        this.jpaRepository = jpaRepository;
        this.objectMapper = objectMapper;
    }
    
    @Override
    public Optional<OutboxEntry> findById(String id) {
        return jpaRepository.findById(id).map(this::toModel);
    }
    
    @Override
    public List<OutboxEntry> findByIdempotencyKey(String idempotencyKey) {
        return jpaRepository.findByIdempotencyKey(idempotencyKey).stream()
                .map(this::toModel)
                .collect(Collectors.toList());
    }
    
    @Override
    @Transactional
    public OutboxEntry save(OutboxEntry entry) {
        OutboxEntryEntity entity = toEntity(entry);
        entity = jpaRepository.save(entity);
        return toModel(entity);
    }
    
    @Override
    public List<OutboxEntry> findReadyForProcessing(int limit) {
        return jpaRepository.findReadyForProcessing(Instant.now(), PageRequest.of(0, limit)).stream()
                .map(this::toModel)
                .collect(Collectors.toList());
    }
    
    @Override
    @Transactional
    public boolean tryLock(String entryId, String lockedBy, int lockDurationSeconds) {
        Instant now = Instant.now();
        Instant lockExpires = now.plusSeconds(lockDurationSeconds);
        return jpaRepository.tryLock(entryId, lockedBy, lockExpires, now) > 0;
    }
    
    @Override
    @Transactional
    public boolean releaseLock(String entryId, String lockedBy) {
        return jpaRepository.releaseLock(entryId, lockedBy) > 0;
    }
    
    @Override
    public List<OutboxEntry> findExpiredLocks() {
        return jpaRepository.findExpiredLocks(Instant.now()).stream()
                .map(this::toModel)
                .collect(Collectors.toList());
    }
    
    @Override
    @Transactional
    public boolean markDelivered(String entryId) {
        return jpaRepository.markDelivered(entryId, Instant.now()) > 0;
    }
    
    @Override
    @Transactional
    public boolean recordFailure(String entryId, String error, Integer httpStatus, Instant nextRetryAt) {
        return jpaRepository.recordFailure(entryId, error, httpStatus, Instant.now(), nextRetryAt) > 0;
    }
    
    @Override
    @Transactional
    public boolean markFailed(String entryId, String error) {
        return jpaRepository.markFailed(entryId, error, Instant.now()) > 0;
    }
    
    @Override
    public List<OutboxEntry> findByStatus(OutboxStatus status, int limit) {
        return jpaRepository.findByStatus(status, PageRequest.of(0, limit)).stream()
                .map(this::toModel)
                .collect(Collectors.toList());
    }
    
    @Override
    public long countByStatus(OutboxStatus status) {
        return jpaRepository.countByStatus(status);
    }
    
    @Override
    public long countByDestination(String destination) {
        return jpaRepository.countByDestination(destination);
    }
    
    @Override
    @Transactional
    public int deleteDeliveredBefore(Instant olderThan) {
        return jpaRepository.deleteDeliveredBefore(olderThan);
    }
    
    @Override
    public double getAverageDeliveryTimeMillis(Instant since) {
        Double avg = jpaRepository.getAverageDeliveryTimeMillis(since);
        return avg != null ? avg : 0;
    }
    
    // Entity <-> Model conversion
    
    private OutboxEntry toModel(OutboxEntryEntity entity) {
        OutboxEntry entry = new OutboxEntry();
        entry.setId(entity.getId());
        entry.setIdempotencyKey(entity.getIdempotencyKey());
        entry.setStatus(entity.getStatus());
        entry.setDestination(entity.getDestination());
        entry.setHttpMethod(entity.getHttpMethod());
        entry.setEndpoint(entity.getEndpoint());
        entry.setPayload(entity.getPayload());
        entry.setHeaders(deserializeHeaders(entity.getHeaders()));
        entry.setCreatedAt(entity.getCreatedAt());
        entry.setLastAttemptAt(entity.getLastAttemptAt());
        entry.setDeliveredAt(entity.getDeliveredAt());
        entry.setNextRetryAt(entity.getNextRetryAt());
        entry.setRetryCount(entity.getRetryCount());
        entry.setLastError(entity.getLastError());
        entry.setLastHttpStatus(entity.getLastHttpStatus());
        entry.setLockedBy(entity.getLockedBy());
        entry.setLockExpiresAt(entity.getLockExpiresAt());
        entry.setVersion(entity.getVersion());
        return entry;
    }
    
    private OutboxEntryEntity toEntity(OutboxEntry model) {
        OutboxEntryEntity entity = new OutboxEntryEntity();
        entity.setId(model.getId());
        entity.setIdempotencyKey(model.getIdempotencyKey());
        entity.setStatus(model.getStatus());
        entity.setDestination(model.getDestination());
        entity.setHttpMethod(model.getHttpMethod());
        entity.setEndpoint(model.getEndpoint());
        entity.setPayload(model.getPayload());
        entity.setHeaders(serializeHeaders(model.getHeaders()));
        entity.setCreatedAt(model.getCreatedAt());
        entity.setLastAttemptAt(model.getLastAttemptAt());
        entity.setDeliveredAt(model.getDeliveredAt());
        entity.setNextRetryAt(model.getNextRetryAt());
        entity.setRetryCount(model.getRetryCount());
        entity.setLastError(model.getLastError());
        entity.setLastHttpStatus(model.getLastHttpStatus());
        entity.setLockedBy(model.getLockedBy());
        entity.setLockExpiresAt(model.getLockExpiresAt());
        entity.setVersion(model.getVersion());
        return entity;
    }
    
    private String serializeHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(headers);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize headers", e);
        }
    }
    
    private Map<String, String> deserializeHeaders(String headersJson) {
        if (headersJson == null || headersJson.isEmpty()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(headersJson, new TypeReference<Map<String, String>>() {});
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize headers", e);
        }
    }
}
