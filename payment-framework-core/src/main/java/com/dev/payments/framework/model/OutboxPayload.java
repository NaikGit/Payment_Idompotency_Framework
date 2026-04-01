package com.dev.payments.framework.model;

import org.springframework.http.HttpMethod;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Represents the payload to be delivered to the downstream system.
 * 
 * Teams build this object in their handler to describe:
 * - Where to send the request (endpoint)
 * - How to send it (HTTP method, headers)
 * - What to send (body)
 * 
 * Example:
 * <pre>
 * {@code
 * OutboxPayload.builder()
 *     .endpoint("https://ledger.internal/api/v1/transactions")
 *     .method(HttpMethod.POST)
 *     .body(ledgerRequest)
 *     .addHeader("X-Correlation-Id", correlationId)
 *     .addHeader("Content-Type", "application/json")
 *     .timeout(Duration.ofSeconds(30))
 *     .build();
 * }
 * </pre>
 */
public class OutboxPayload {
    
    /**
     * The endpoint URL for REST calls.
     */
    private String endpoint;
    
    /**
     * HTTP method (default: POST).
     */
    private HttpMethod method = HttpMethod.POST;
    
    /**
     * The body to send. Will be serialized to JSON.
     */
    private Object body;
    
    /**
     * HTTP headers to include.
     */
    private Map<String, String> headers = new HashMap<>();
    
    /**
     * Request timeout in milliseconds (default: 30000).
     */
    private long timeoutMillis = 30000;
    
    /**
     * For messaging destinations: topic/queue name.
     */
    private String messagingDestination;
    
    /**
     * Delivery type (REST or MESSAGING).
     */
    private DeliveryType deliveryType = DeliveryType.REST;
    
    /**
     * Custom metadata for tracking/debugging.
     */
    private Map<String, String> metadata = new HashMap<>();
    
    public enum DeliveryType {
        REST,
        MESSAGING
    }
    
    // Default constructor
    public OutboxPayload() {
    }
    
    // Builder pattern
    public static Builder builder() {
        return new Builder();
    }
    
    /**
     * Convenience method for REST POST.
     */
    public static OutboxPayload restPost(String endpoint, Object body) {
        return builder()
                .endpoint(endpoint)
                .method(HttpMethod.POST)
                .body(body)
                .build();
    }
    
    /**
     * Convenience method for messaging.
     */
    public static OutboxPayload messaging(String destination, Object body) {
        return builder()
                .deliveryType(DeliveryType.MESSAGING)
                .messagingDestination(destination)
                .body(body)
                .build();
    }
    
    public static class Builder {
        private final OutboxPayload payload = new OutboxPayload();
        
        public Builder endpoint(String endpoint) {
            payload.endpoint = endpoint;
            payload.deliveryType = DeliveryType.REST;
            return this;
        }
        
        public Builder method(HttpMethod method) {
            payload.method = method;
            return this;
        }
        
        public Builder body(Object body) {
            payload.body = body;
            return this;
        }
        
        public Builder headers(Map<String, String> headers) {
            payload.headers = headers != null ? new HashMap<>(headers) : new HashMap<>();
            return this;
        }
        
        public Builder addHeader(String key, String value) {
            payload.headers.put(key, value);
            return this;
        }
        
        public Builder contentTypeJson() {
            payload.headers.put("Content-Type", "application/json");
            return this;
        }
        
        public Builder timeoutMillis(long timeoutMillis) {
            payload.timeoutMillis = timeoutMillis;
            return this;
        }
        
        public Builder timeoutSeconds(int seconds) {
            payload.timeoutMillis = seconds * 1000L;
            return this;
        }
        
        public Builder messagingDestination(String destination) {
            payload.messagingDestination = destination;
            payload.deliveryType = DeliveryType.MESSAGING;
            return this;
        }
        
        public Builder deliveryType(DeliveryType type) {
            payload.deliveryType = type;
            return this;
        }
        
        public Builder metadata(Map<String, String> metadata) {
            payload.metadata = metadata != null ? new HashMap<>(metadata) : new HashMap<>();
            return this;
        }
        
        public Builder addMetadata(String key, String value) {
            payload.metadata.put(key, value);
            return this;
        }
        
        public OutboxPayload build() {
            // Validate based on delivery type
            if (payload.deliveryType == DeliveryType.REST) {
                Objects.requireNonNull(payload.endpoint, "endpoint is required for REST delivery");
            } else if (payload.deliveryType == DeliveryType.MESSAGING) {
                Objects.requireNonNull(payload.messagingDestination, "messagingDestination is required for MESSAGING delivery");
            }
            Objects.requireNonNull(payload.body, "body is required");
            
            // Add default headers
            if (!payload.headers.containsKey("Content-Type") && payload.deliveryType == DeliveryType.REST) {
                payload.headers.put("Content-Type", "application/json");
            }
            
            return payload;
        }
    }
    
    // Getters and Setters
    
    public String getEndpoint() {
        return endpoint;
    }
    
    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }
    
    public HttpMethod getMethod() {
        return method;
    }
    
    public void setMethod(HttpMethod method) {
        this.method = method;
    }
    
    public Object getBody() {
        return body;
    }
    
    public void setBody(Object body) {
        this.body = body;
    }
    
    public Map<String, String> getHeaders() {
        return headers;
    }
    
    public void setHeaders(Map<String, String> headers) {
        this.headers = headers;
    }
    
    public long getTimeoutMillis() {
        return timeoutMillis;
    }
    
    public void setTimeoutMillis(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }
    
    public String getMessagingDestination() {
        return messagingDestination;
    }
    
    public void setMessagingDestination(String messagingDestination) {
        this.messagingDestination = messagingDestination;
    }
    
    public DeliveryType getDeliveryType() {
        return deliveryType;
    }
    
    public void setDeliveryType(DeliveryType deliveryType) {
        this.deliveryType = deliveryType;
    }
    
    public Map<String, String> getMetadata() {
        return metadata;
    }
    
    public void setMetadata(Map<String, String> metadata) {
        this.metadata = metadata;
    }
    
    @Override
    public String toString() {
        return "OutboxPayload{" +
                "deliveryType=" + deliveryType +
                ", endpoint='" + endpoint + '\'' +
                ", method=" + method +
                ", messagingDestination='" + messagingDestination + '\'' +
                '}';
    }
}
