package com.dev.payments.framework.jpa.repository;

import com.dev.payments.framework.jpa.entity.OutboxEntryEntity;
import com.dev.payments.framework.model.OutboxEntry;
import com.dev.payments.framework.model.OutboxEntry.OutboxStatus;
import com.dev.payments.framework.repository.OutboxRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * JPA implementation of OutboxRepository.
 *
 * Critical invariants upheld here:
 *
 * tryLock() delegates to a single @Modifying @Query that is one atomic conditional UPDATE.
 * There is no read-then-write. Two concurrent callers race at the database level; the one
 * whose UPDATE matches first (lockedBy IS NULL or lock expired) wins. The other sees
 * rowsAffected = 0 and returns false.
 *
 * releaseLock() includes AND lockedBy = :lockedBy in its WHERE clause so a node that
 * died and was replaced cannot accidentally clear the replacement's lock.
 *
 * All mutating methods are @Transactional. Read-only methods use the default
 * read-only transaction provided by Spring Data JPA.
 */
@Component
public class JpaOutboxRepository implements OutboxRepository {

    private final OutboxEntryJpaRepository jpaRepository;

    public JpaOutboxRepository(OutboxEntryJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
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
        Instant lockExpiresAt = now.plusSeconds(lockDurationSeconds);
        return jpaRepository.tryLock(entryId, lockedBy, lockExpiresAt, now) > 0;
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
        OutboxStatus newStatus = nextRetryAt != null ? OutboxStatus.RETRY_PENDING : OutboxStatus.FAILED;
        return jpaRepository.recordFailure(entryId, error, httpStatus, Instant.now(), nextRetryAt, newStatus) > 0;
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
        Double result = jpaRepository.getAverageDeliveryTimeMillis(since);
        return result != null ? result : 0.0;
    }

    // -------------------------------------------------------------------------
    // Entity <-> Model conversion
    // -------------------------------------------------------------------------

    private OutboxEntry toModel(OutboxEntryEntity entity) {
        OutboxEntry entry = OutboxEntry.builder()
                .id(entity.getId())
                .idempotencyKey(entity.getIdempotencyKey())
                .status(entity.getStatus())
                .destination(entity.getDestination())
                .httpMethod(entity.getHttpMethod())
                .endpoint(entity.getEndpoint())
                .payload(entity.getPayload())
                .createdAt(entity.getCreatedAt())
                .build();
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
        entity.setCreatedAt(model.getCreatedAt());
        entity.setLastAttemptAt(model.getLastAttemptAt());
        entity.setDeliveredAt(model.getDeliveredAt());
        entity.setNextRetryAt(model.getNextRetryAt());
        entity.setRetryCount(model.getRetryCount());
        entity.setLastError(model.getLastError());
        entity.setLastHttpStatus(model.getLastHttpStatus());
        entity.setLockedBy(model.getLockedBy());
        entity.setLockExpiresAt(model.getLockExpiresAt());
        return entity;
    }
}
