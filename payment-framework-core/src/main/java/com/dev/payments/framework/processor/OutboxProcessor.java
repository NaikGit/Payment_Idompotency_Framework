package com.dev.payments.framework.processor;

import com.dev.payments.framework.config.OutboxProcessorConfig;
import com.dev.payments.framework.model.OutboxEntry;
import com.dev.payments.framework.model.OutboxEntry.OutboxStatus;
import com.dev.payments.framework.repository.OutboxRepository;
import com.dev.payments.framework.repository.ProcessedEventRepository;
import com.dev.payments.framework.service.DeliveryService;
import com.dev.payments.framework.state.PaymentState;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Processes outbox entries and delivers them to downstream systems.
 * 
 * Features:
 * - Polls for pending entries at configurable intervals
 * - Uses row-level locking to prevent concurrent processing
 * - Implements exponential backoff for retries
 * - Recovers from expired locks (processor crash recovery)
 * - Emits Prometheus metrics
 * 
 * Thread Safety: Uses database locks for coordination; safe for multiple instances.
 */
public class OutboxProcessor {
    
    private static final Logger log = LoggerFactory.getLogger(OutboxProcessor.class);
    
    private final OutboxRepository outboxRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final DeliveryService deliveryService;
    private final OutboxProcessorConfig config;
    private final MeterRegistry meterRegistry;
    private final String processorId;
    
    private ExecutorService executorService;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger activeDeliveries = new AtomicInteger(0);
    
    // Metrics
    private Counter deliverySuccessCounter;
    private Counter deliveryFailureCounter;
    private Counter deliveryRetryCounter;
    private Counter permanentFailureCounter;
    private Timer deliveryTimer;
    
    public OutboxProcessor(
            OutboxRepository outboxRepository,
            ProcessedEventRepository processedEventRepository,
            DeliveryService deliveryService,
            OutboxProcessorConfig config,
            MeterRegistry meterRegistry) {
        this.outboxRepository = outboxRepository;
        this.processedEventRepository = processedEventRepository;
        this.deliveryService = deliveryService;
        this.config = config;
        this.meterRegistry = meterRegistry;
        this.processorId = resolveProcessorId();
    }
    
    @PostConstruct
    public void initialize() {
        if (!config.isEnabled()) {
            log.info("Outbox processor is disabled");
            return;
        }
        
        // Initialize thread pool
        executorService = Executors.newFixedThreadPool(config.getWorkerThreads());
        
        // Initialize metrics
        initializeMetrics();
        
        running.set(true);
        log.info("Outbox processor initialized with {} worker threads", config.getWorkerThreads());
    }
    
    @PreDestroy
    public void shutdown() {
        running.set(false);
        if (executorService != null) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        log.info("Outbox processor shutdown complete");
    }
    
    private void initializeMetrics() {
        deliverySuccessCounter = Counter.builder("payment.outbox.delivery.success")
                .description("Number of successful deliveries")
                .register(meterRegistry);
        
        deliveryFailureCounter = Counter.builder("payment.outbox.delivery.failure")
                .description("Number of failed deliveries")
                .register(meterRegistry);
        
        deliveryRetryCounter = Counter.builder("payment.outbox.delivery.retry")
                .description("Number of delivery retries")
                .register(meterRegistry);
        
        permanentFailureCounter = Counter.builder("payment.outbox.delivery.permanent_failure")
                .description("Number of permanent failures (max retries exceeded)")
                .register(meterRegistry);
        
        deliveryTimer = Timer.builder("payment.outbox.delivery.time")
                .description("Time taken to deliver outbox entries")
                .register(meterRegistry);
        
        // Gauge for pending entries
        Gauge.builder("payment.outbox.pending", outboxRepository, 
                repo -> repo.countByStatus(OutboxStatus.PENDING))
                .description("Number of pending outbox entries")
                .register(meterRegistry);
        
        // Gauge for active deliveries
        Gauge.builder("payment.outbox.active_deliveries", activeDeliveries, AtomicInteger::get)
                .description("Number of deliveries currently in progress")
                .register(meterRegistry);
        
        // Gauge for failed entries
        Gauge.builder("payment.outbox.failed", outboxRepository,
                repo -> repo.countByStatus(OutboxStatus.FAILED))
                .description("Number of permanently failed outbox entries")
                .register(meterRegistry);
    }
    
    /**
     * Main polling loop - runs at fixed interval.
     */
    @Scheduled(fixedDelayString = "${payment.framework.outbox.poll-interval-ms:1000}")
    public void poll() {
        if (!running.get() || !config.isEnabled()) {
            return;
        }
        
        try {
            // Step 1: Recover expired locks
            recoverExpiredLocks();
            
            // Step 2: Find entries ready for processing
            List<OutboxEntry> entries = outboxRepository.findReadyForProcessing(config.getBatchSize());
            
            if (entries.isEmpty()) {
                return;
            }
            
            log.debug("Found {} entries ready for processing", entries.size());
            
            // Step 3: Process each entry
            for (OutboxEntry entry : entries) {
                if (!running.get()) {
                    break;
                }
                processEntry(entry);
            }
            
        } catch (Exception e) {
            log.error("Error in outbox processor poll", e);
        }
    }
    
    /**
     * Process a single outbox entry.
     */
    private void processEntry(OutboxEntry entry) {
        // Try to acquire lock
        if (!outboxRepository.tryLock(entry.getId(), processorId, config.getLockDurationSeconds())) {
            log.debug("Could not acquire lock for entry: {}", entry.getId());
            return;
        }
        
        // Submit for async processing
        executorService.submit(() -> {
            activeDeliveries.incrementAndGet();
            try {
                deliverEntry(entry);
            } finally {
                activeDeliveries.decrementAndGet();
            }
        });
    }
    
    /**
     * Deliver an entry to the downstream system.
     */
    private void deliverEntry(OutboxEntry entry) {
        log.info("Delivering entry: {} to {}", entry.getId(), entry.getDestination());
        
        Timer.Sample sample = Timer.start(meterRegistry);
        
        try {
            // Attempt delivery
            DeliveryService.DeliveryResult result = deliveryService.deliver(entry);
            
            if (result.isSuccess()) {
                // Mark as delivered
                outboxRepository.markDelivered(entry.getId());
                processedEventRepository.markCompleted(entry.getIdempotencyKey());
                
                deliverySuccessCounter.increment();
                log.info("Successfully delivered entry: {} (took {}ms)", 
                        entry.getId(), result.getDurationMillis());
                
            } else {
                handleDeliveryFailure(entry, result);
            }
            
        } catch (Exception e) {
            log.error("Unexpected error delivering entry: {}", entry.getId(), e);
            handleDeliveryFailure(entry, DeliveryService.DeliveryResult.failure(
                    "Unexpected error: " + e.getMessage(), null));
            
        } finally {
            sample.stop(deliveryTimer);
            outboxRepository.releaseLock(entry.getId(), processorId);
        }
    }
    
    /**
     * Handle a failed delivery attempt.
     */
    private void handleDeliveryFailure(OutboxEntry entry, DeliveryService.DeliveryResult result) {
        int newRetryCount = entry.getRetryCount() + 1;
        
        if (newRetryCount >= config.getMaxRetries()) {
            // Permanent failure
            outboxRepository.markFailed(entry.getId(), result.getError());
            processedEventRepository.updateState(
                    entry.getIdempotencyKey(), PaymentState.FAILED, result.getError());
            
            permanentFailureCounter.increment();
            log.error("Permanent failure for entry: {} after {} attempts. Error: {}", 
                    entry.getId(), newRetryCount, result.getError());
            
        } else {
            // Schedule retry
            Instant nextRetry = calculateNextRetry(newRetryCount);
            outboxRepository.recordFailure(
                    entry.getId(), result.getError(), result.getHttpStatus(), nextRetry);
            
            deliveryRetryCounter.increment();
            log.warn("Delivery failed for entry: {} (attempt {}/{}). Retry at: {}. Error: {}", 
                    entry.getId(), newRetryCount, config.getMaxRetries(), 
                    nextRetry, result.getError());
        }
        
        deliveryFailureCounter.increment();
    }
    
    /**
     * Calculate next retry time using exponential backoff.
     */
    private Instant calculateNextRetry(int retryCount) {
        int delay = Math.min(
                config.getRetryBaseDelaySeconds() * (int) Math.pow(2, retryCount - 1),
                config.getRetryMaxDelaySeconds());
        return Instant.now().plusSeconds(delay);
    }
    
    /**
     * Recover entries with expired locks (crash recovery).
     */
    private void recoverExpiredLocks() {
        try {
            List<OutboxEntry> expiredEntries = outboxRepository.findExpiredLocks();
            for (OutboxEntry entry : expiredEntries) {
                log.warn("Recovering entry with expired lock: {} (was locked by: {})", 
                        entry.getId(), entry.getLockedBy());
                outboxRepository.releaseLock(entry.getId(), entry.getLockedBy());
            }
        } catch (Exception e) {
            log.error("Error recovering expired locks", e);
        }
    }
    
    private String resolveProcessorId() {
        try {
            String hostname = InetAddress.getLocalHost().getHostName();
            return hostname + "-" + ProcessHandle.current().pid();
        } catch (UnknownHostException e) {
            return "processor-" + System.currentTimeMillis();
        }
    }
    
    /**
     * Get the processor status (for health checks).
     */
    public ProcessorStatus getStatus() {
        return new ProcessorStatus(
                running.get(),
                config.isEnabled(),
                activeDeliveries.get(),
                outboxRepository.countByStatus(OutboxStatus.PENDING),
                outboxRepository.countByStatus(OutboxStatus.FAILED)
        );
    }
    
    public static class ProcessorStatus {
        private final boolean running;
        private final boolean enabled;
        private final int activeDeliveries;
        private final long pendingEntries;
        private final long failedEntries;
        
        public ProcessorStatus(boolean running, boolean enabled, int activeDeliveries,
                               long pendingEntries, long failedEntries) {
            this.running = running;
            this.enabled = enabled;
            this.activeDeliveries = activeDeliveries;
            this.pendingEntries = pendingEntries;
            this.failedEntries = failedEntries;
        }
        
        public boolean isRunning() {
            return running;
        }
        
        public boolean isEnabled() {
            return enabled;
        }
        
        public int getActiveDeliveries() {
            return activeDeliveries;
        }
        
        public long getPendingEntries() {
            return pendingEntries;
        }
        
        public long getFailedEntries() {
            return failedEntries;
        }
        
        public boolean isHealthy() {
            return enabled && running;
        }
    }
}
