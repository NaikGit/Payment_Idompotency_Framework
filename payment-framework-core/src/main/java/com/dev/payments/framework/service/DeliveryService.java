package com.dev.payments.framework.service;

import com.dev.payments.framework.model.OutboxEntry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.List;

/**
 * Service for delivering outbox entries to downstream systems.
 * 
 * Supports:
 * - REST API calls (POST, PUT, PATCH)
 * - Configurable timeouts
 * - Retry-safe (idempotent delivery)
 * 
 * Thread Safety: Thread-safe; designed for concurrent delivery.
 */
public class DeliveryService {
    
    private static final Logger log = LoggerFactory.getLogger(DeliveryService.class);
    
    private final WebClient webClient;
    private final MeterRegistry meterRegistry;
    private final Duration defaultTimeout;
    
    public DeliveryService(WebClient.Builder webClientBuilder, 
                           MeterRegistry meterRegistry,
                           Duration defaultTimeout) {
        this.webClient = webClientBuilder
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.meterRegistry = meterRegistry;
        this.defaultTimeout = defaultTimeout;
    }
    
    /**
     * Deliver an outbox entry to its destination.
     * 
     * @param entry The entry to deliver
     * @return DeliveryResult indicating success or failure
     */
    public DeliveryResult deliver(OutboxEntry entry) {
        long startTime = System.currentTimeMillis();
        
        try {
            switch (entry.getDeliveryType()) {
                case "MESSAGING":
                    return deliverViaMessaging(entry);
                case "REST":
                default:
                    return deliverViaRest(entry);
            }
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            recordDeliveryMetrics(entry, duration);
        }
    }
    
    /**
     * Deliver via REST API.
     */
    private DeliveryResult deliverViaRest(OutboxEntry entry) {
        try {
            HttpMethod method = HttpMethod.valueOf(
                    entry.getHttpMethod() != null ? entry.getHttpMethod() : "POST");
            
            WebClient.RequestBodySpec request = webClient
                    .method(method)
                    .uri(entry.getEndpoint())
                    .headers(headers -> {
                        if (entry.getHeaders() != null) {
                            entry.getHeaders().forEach(headers::add);
                        }
                        // Add idempotency key header
                        headers.add("X-Idempotency-Key", entry.getIdempotencyKey());
                    });
            
            // Make the request
            String response = request
                    .bodyValue(entry.getPayload())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(defaultTimeout);
            
            log.debug("Delivery successful for entry: {}. Response: {}", 
                    entry.getId(), truncate(response, 200));
            
            return DeliveryResult.success(System.currentTimeMillis());
            
        } catch (WebClientResponseException e) {
            int statusCode = e.getStatusCode().value();
            String errorBody = e.getResponseBodyAsString();
            
            log.warn("Delivery failed for entry: {}. Status: {}, Body: {}", 
                    entry.getId(), statusCode, truncate(errorBody, 500));
            
            // Check if retryable
            if (isRetryableStatus(e.getStatusCode())) {
                return DeliveryResult.failure(
                        "HTTP " + statusCode + ": " + truncate(errorBody, 200), statusCode);
            } else {
                // Non-retryable (4xx except 429)
                return DeliveryResult.permanentFailure(
                        "HTTP " + statusCode + ": " + truncate(errorBody, 200), statusCode);
            }
            
        } catch (Exception e) {
            // Broad catch is intentional: WebClient.block() can throw reactor internals
            // (e.g. ReactiveException wrapping TimeoutException) that have no common supertype.
            log.error("Unexpected error delivering entry: {}", entry.getId(), e);
            return DeliveryResult.failure(
                    "Error: " + e.getClass().getSimpleName() + ": " + e.getMessage(), null);
        }
    }
    
    /**
     * Deliver via messaging (to be implemented by messaging adapters).
     */
    private DeliveryResult deliverViaMessaging(OutboxEntry entry) {
        // This will be handled by messaging-specific adapters
        log.warn("Messaging delivery not implemented in base DeliveryService. " +
                "Use IBM MQ or Kafka adapter.");
        return DeliveryResult.failure("Messaging delivery not configured", null);
    }
    
    /**
     * Check if HTTP status is retryable.
     */
    private boolean isRetryableStatus(HttpStatusCode status) {
        int code = status.value();
        // Retry on:
        // - 429 Too Many Requests
        // - 5xx Server errors
        // - 408 Request Timeout
        return code == 429 || code == 408 || (code >= 500 && code < 600);
    }
    
    private void recordDeliveryMetrics(OutboxEntry entry, long durationMs) {
        Timer.builder("payment.delivery.duration")
                .tags(List.of(
                        Tag.of("destination", entry.getDestination()),
                        Tag.of("method", entry.getHttpMethod() != null ? entry.getHttpMethod() : "POST")
                ))
                .register(meterRegistry)
                .record(Duration.ofMillis(durationMs));
    }
    
    private String truncate(String s, int maxLength) {
        if (s == null) return null;
        return s.length() > maxLength ? s.substring(0, maxLength) + "..." : s;
    }
    
    /**
     * Result of a delivery attempt.
     */
    public record DeliveryResult(
            boolean success,
            boolean permanentFailure,
            String error,
            Integer httpStatus,
            long durationMillis) {

        public static DeliveryResult success(long durationMillis) {
            return new DeliveryResult(true, false, null, 200, durationMillis);
        }

        public static DeliveryResult failure(String error, Integer httpStatus) {
            return new DeliveryResult(false, false, error, httpStatus, 0);
        }

        public static DeliveryResult permanentFailure(String error, Integer httpStatus) {
            return new DeliveryResult(false, true, error, httpStatus, 0);
        }

        public boolean isSuccess() { return success; }
        public boolean isPermanentFailure() { return permanentFailure; }
        public String getError() { return error; }
        public Integer getHttpStatus() { return httpStatus; }
        public long getDurationMillis() { return durationMillis; }
    }
}
