package com.dev.payments.framework.mongodb.repository;

import com.dev.payments.framework.mongodb.document.OutboxEntryDocument;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

/**
 * Spring Data MongoDB repository for OutboxEntryDocument.
 *
 * Complex queries (findReadyForProcessing, findExpiredLocks, tryLock, aggregations)
 * are handled in MongoOutboxRepository via MongoTemplate for atomicity and precision.
 */
public interface OutboxEntryMongoStore extends MongoRepository<OutboxEntryDocument, String> {

    List<OutboxEntryDocument> findByIdempotencyKey(String idempotencyKey);

    List<OutboxEntryDocument> findByStatus(String status, Sort sort);

    long countByStatus(String status);

    long countByDestination(String destination);
}
