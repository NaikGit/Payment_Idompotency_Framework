package com.dev.payments.framework.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the outbox processor.
 * 
 * Example application.yml:
 * <pre>
 * payment:
 *   framework:
 *     outbox:
 *       enabled: true
 *       poll-interval-ms: 1000
 *       batch-size: 10
 *       worker-threads: 4
 *       max-retries: 5
 *       retry-base-delay-seconds: 30
 *       retry-max-delay-seconds: 3600
 *       lock-duration-seconds: 60
 *       delivery-timeout-seconds: 30
 * </pre>
 */
@ConfigurationProperties(prefix = "payment.framework.outbox")
public class OutboxProcessorConfig {
    
    /**
     * Whether the outbox processor is enabled.
     * Set to false if running processor as a separate service.
     */
    private boolean enabled = true;
    
    /**
     * Polling interval in milliseconds.
     */
    private long pollIntervalMs = 1000;
    
    /**
     * Number of entries to fetch per poll.
     */
    private int batchSize = 10;
    
    /**
     * Number of worker threads for parallel delivery.
     */
    private int workerThreads = 4;
    
    /**
     * Maximum number of delivery retries.
     */
    private int maxRetries = 5;
    
    /**
     * Base delay for exponential backoff (seconds).
     */
    private int retryBaseDelaySeconds = 30;
    
    /**
     * Maximum delay for exponential backoff (seconds).
     */
    private int retryMaxDelaySeconds = 3600;
    
    /**
     * How long a processor holds a lock (seconds).
     */
    private int lockDurationSeconds = 60;
    
    /**
     * HTTP delivery timeout (seconds).
     */
    private int deliveryTimeoutSeconds = 30;
    
    /**
     * Retention period for delivered entries (hours).
     * Entries older than this will be cleaned up.
     */
    private int retentionHours = 168; // 7 days
    
    // Getters and Setters
    
    public boolean isEnabled() {
        return enabled;
    }
    
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
    
    public long getPollIntervalMs() {
        return pollIntervalMs;
    }
    
    public void setPollIntervalMs(long pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }
    
    public int getBatchSize() {
        return batchSize;
    }
    
    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }
    
    public int getWorkerThreads() {
        return workerThreads;
    }
    
    public void setWorkerThreads(int workerThreads) {
        this.workerThreads = workerThreads;
    }
    
    public int getMaxRetries() {
        return maxRetries;
    }
    
    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }
    
    public int getRetryBaseDelaySeconds() {
        return retryBaseDelaySeconds;
    }
    
    public void setRetryBaseDelaySeconds(int retryBaseDelaySeconds) {
        this.retryBaseDelaySeconds = retryBaseDelaySeconds;
    }
    
    public int getRetryMaxDelaySeconds() {
        return retryMaxDelaySeconds;
    }
    
    public void setRetryMaxDelaySeconds(int retryMaxDelaySeconds) {
        this.retryMaxDelaySeconds = retryMaxDelaySeconds;
    }
    
    public int getLockDurationSeconds() {
        return lockDurationSeconds;
    }
    
    public void setLockDurationSeconds(int lockDurationSeconds) {
        this.lockDurationSeconds = lockDurationSeconds;
    }
    
    public int getDeliveryTimeoutSeconds() {
        return deliveryTimeoutSeconds;
    }
    
    public void setDeliveryTimeoutSeconds(int deliveryTimeoutSeconds) {
        this.deliveryTimeoutSeconds = deliveryTimeoutSeconds;
    }
    
    public int getRetentionHours() {
        return retentionHours;
    }
    
    public void setRetentionHours(int retentionHours) {
        this.retentionHours = retentionHours;
    }
}
