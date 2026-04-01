package com.dev.payments.framework.mongodb.repository;

import com.dev.payments.framework.mongodb.document.ProcessedEventDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

/**
 * Spring Data MongoDB repository for ProcessedEventDocument.
 *
 * Derived queries handle the read-only operations. All mutating operations
 * (updateState, markCompleted, deleteCompletedBefore) go through MongoTemplate
 * in MongoProcessedEventRepository to keep updates atomic.
 */
public interface ProcessedEventMongoStore extends MongoRepository<ProcessedEventDocument, String> {

    boolean existsByIdempotencyKey(String idempotencyKey);

    List<ProcessedEventDocument> findByState(String state);

    long countByState(String state);
}
