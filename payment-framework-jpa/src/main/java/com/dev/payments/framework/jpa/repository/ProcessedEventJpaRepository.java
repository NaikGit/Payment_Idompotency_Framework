package com.dev.payments.framework.jpa.repository;

import com.dev.payments.framework.jpa.entity.ProcessedEventEntity;
import com.dev.payments.framework.state.PaymentState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Spring Data JPA repository for ProcessedEventEntity.
 */
@Repository
public interface ProcessedEventJpaRepository extends JpaRepository<ProcessedEventEntity, String> {
    
    boolean existsByIdempotencyKey(String idempotencyKey);
    
    List<ProcessedEventEntity> findByState(PaymentState state);
    
    @Query("SELECT e FROM ProcessedEventEntity e WHERE e.state = 'PROCESSING' AND e.receivedAt < :olderThan")
    List<ProcessedEventEntity> findStuckProcessing(@Param("olderThan") Instant olderThan);
    
    @Query("SELECT e FROM ProcessedEventEntity e WHERE e.state = 'FAILED' ORDER BY e.receivedAt DESC")
    List<ProcessedEventEntity> findRecentFailures(org.springframework.data.domain.Pageable pageable);
    
    long countByState(PaymentState state);
    
    @Modifying
    @Query("UPDATE ProcessedEventEntity e SET e.state = :state, e.lastError = :error WHERE e.idempotencyKey = :key")
    int updateState(@Param("key") String idempotencyKey, 
                    @Param("state") PaymentState state, 
                    @Param("error") String error);
    
    @Modifying
    @Query("UPDATE ProcessedEventEntity e SET e.state = 'COMPLETED', e.completedAt = :completedAt WHERE e.idempotencyKey = :key")
    int markCompleted(@Param("key") String idempotencyKey, @Param("completedAt") Instant completedAt);
    
    @Modifying
    @Query("DELETE FROM ProcessedEventEntity e WHERE e.state = 'COMPLETED' AND e.completedAt < :olderThan")
    int deleteCompletedBefore(@Param("olderThan") Instant olderThan);
}
