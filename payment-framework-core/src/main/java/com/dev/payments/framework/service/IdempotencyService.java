package com.dev.payments.framework.service;

import com.dev.payments.framework.model.OutboxEntry;
import com.dev.payments.framework.model.OutboxPayload;
import com.dev.payments.framework.model.ProcessedEvent;
import com.dev.payments.framework.repository.OutboxRepository;
import com.dev.payments.framework.repository.ProcessedEventRepository;
import com.dev.payments.framework.state.PaymentState;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.Optional;

/**
 * Core service for idempotency checking and outbox management.
 * 
 * This service ensures that:
 * 1. Each message is processed exactly once
 * 2. State is persisted before any downstream calls
 * 3. Outbox entries are created atomically with state
 * 
 * Thread Safety: This service is thread-safe and designed for concurrent access.
 */
public class IdempotencyService {
    
    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    
    private final ProcessedEventRepository processedEventRepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final String nodeId;
    
    // Metrics
    private final Counter duplicatesDetectedCounter;
    private final Counter eventsProcessedCounter;
    private final Counter outboxEntriesCreatedCounter;
    private final Timer processingTimer;
    
    public IdempotencyService(
            ProcessedEventRepository processedEventRepository,
            OutboxRepository outboxRepository,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.processedEventRepository = processedEventRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.nodeId = resolveNodeId();
        
        // Initialize metrics
        this.duplicatesDetectedCounter = Counter.builder("payment.idempotency.duplicates")
                .description("Number of duplicate messages detected")
                .register(meterRegistry);
        
        this.eventsProcessedCounter = Counter.builder("payment.idempotency.processed")
                .description("Number of events successfully processed")
                .register(meterRegistry);
        
        this.outboxEntriesCreatedCounter = Counter.builder("payment.outbox.entries.created")
                .description("Number of outbox entries created")
                .register(meterRegistry);
        
        this.processingTimer = Timer.builder("payment.idempotency.processing.time")
                .description("Time taken to process idempotency check and outbox creation")
                .register(meterRegistry);
    }
    
    /**
     * Check if a message has already been processed.
     * 
     * @param idempotencyKey The unique key for this message
     * @return Optional containing the existing event if found
     */
    public Optional<ProcessedEvent> checkDuplicate(String idempotencyKey) {
        Optional<ProcessedEvent> existing = processedEventRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            duplicatesDetectedCounter.increment();
            log.info("Duplicate detected for key: {} (state: {})", 
                    idempotencyKey, existing.get().getState());
        }
        return existing;
    }
    
    /**
     * Check if a message has already been processed (existence check only).
     * More efficient when you don't need the full event.
     * 
     * @param idempotencyKey The unique key for this message
     * @return true if already processed
     */
    public boolean isDuplicate(String idempotencyKey) {
        boolean exists = processedEventRepository.existsByIdempotencyKey(idempotencyKey);
        if (exists) {
            duplicatesDetectedCounter.increment();
            log.info("Duplicate detected for key: {}", idempotencyKey);
        }
        return exists;
    }
    
    /**
     * Process a new message: create idempotency record and outbox entry atomically.
     * 
     * This method is transactional and will:
     * 1. Create a ProcessedEvent in PROCESSING state
     * 2. Create an OutboxEntry in PENDING state
     * 3. Commit both atomically
     * 
     * If a duplicate is detected, this method returns the existing event
     * instead of creating new records.
     * 
     * @param idempotencyKey The unique key for this message
     * @param source The source queue/topic
     * @param destination The destination system
     * @param outboxPayload The payload to deliver
     * @return ProcessingResult containing the outcome
     */
    @Transactional
    public ProcessingResult processMessage(
            String idempotencyKey,
            String source,
            String destination,
            OutboxPayload outboxPayload) {
        
        return processingTimer.record(() -> {
            // Step 1: Check for duplicate
            Optional<ProcessedEvent> existing = processedEventRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                duplicatesDetectedCounter.increment();
                log.info("Duplicate message skipped: {} (state: {})", 
                        idempotencyKey, existing.get().getState());
                return ProcessingResult.duplicate(existing.get());
            }
            
            // Step 2: Create ProcessedEvent
            ProcessedEvent event = ProcessedEvent.builder()
                    .idempotencyKey(idempotencyKey)
                    .state(PaymentState.PROCESSING)
                    .source(source)
                    .destination(destination)
                    .receivedAt(Instant.now())
                    .processedBy(nodeId)
                    .build();
            
            event = processedEventRepository.save(event);
            
            // Step 3: Create OutboxEntry
            String payloadJson;
            try {
                payloadJson = objectMapper.writeValueAsString(outboxPayload.getBody());
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to serialize outbox payload", e);
            }
            
            OutboxEntry outboxEntry = OutboxEntry.builder()
                    .idempotencyKey(idempotencyKey)
                    .destination(destination)
                    .endpoint(outboxPayload.getEndpoint())
                    .httpMethod(outboxPayload.getMethod() != null ? 
                            outboxPayload.getMethod().name() : "POST")
                    .payload(payloadJson)
                    .headers(outboxPayload.getHeaders())
                    .build();
            
            outboxEntry = outboxRepository.save(outboxEntry);
            
            // Update metrics
            eventsProcessedCounter.increment();
            outboxEntriesCreatedCounter.increment();
            
            log.info("Message processed: {} -> outbox entry: {}", 
                    idempotencyKey, outboxEntry.getId());
            
            return ProcessingResult.success(event, outboxEntry);
        });
    }
    
    /**
     * Mark an event as completed after successful delivery.
     * 
     * @param idempotencyKey The unique key
     * @return true if update succeeded
     */
    @Transactional
    public boolean markCompleted(String idempotencyKey) {
        boolean updated = processedEventRepository.markCompleted(idempotencyKey);
        if (updated) {
            log.info("Event marked completed: {}", idempotencyKey);
        }
        return updated;
    }
    
    /**
     * Record a failure for an event.
     * 
     * @param idempotencyKey The unique key
     * @param error The error message
     * @return true if update succeeded
     */
    @Transactional
    public boolean recordFailure(String idempotencyKey, String error) {
        boolean updated = processedEventRepository.updateState(
                idempotencyKey, PaymentState.FAILED, error);
        if (updated) {
            log.warn("Event marked failed: {} - {}", idempotencyKey, error);
        }
        return updated;
    }
    
    /**
     * Get the current state of an event.
     * 
     * @param idempotencyKey The unique key
     * @return Optional containing the event if found
     */
    public Optional<ProcessedEvent> getEvent(String idempotencyKey) {
        return processedEventRepository.findByIdempotencyKey(idempotencyKey);
    }
    
    private String resolveNodeId() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown-" + System.currentTimeMillis();
        }
    }
    
    /**
     * Result of processing a message.
     */
    public static class ProcessingResult {
        private final boolean success;
        private final boolean duplicate;
        private final ProcessedEvent event;
        private final OutboxEntry outboxEntry;
        
        private ProcessingResult(boolean success, boolean duplicate, 
                                 ProcessedEvent event, OutboxEntry outboxEntry) {
            this.success = success;
            this.duplicate = duplicate;
            this.event = event;
            this.outboxEntry = outboxEntry;
        }
        
        public static ProcessingResult success(ProcessedEvent event, OutboxEntry outboxEntry) {
            return new ProcessingResult(true, false, event, outboxEntry);
        }
        
        public static ProcessingResult duplicate(ProcessedEvent existingEvent) {
            return new ProcessingResult(false, true, existingEvent, null);
        }
        
        public boolean isSuccess() {
            return success;
        }
        
        public boolean isDuplicate() {
            return duplicate;
        }
        
        public ProcessedEvent getEvent() {
            return event;
        }
        
        public OutboxEntry getOutboxEntry() {
            return outboxEntry;
        }
    }
}
