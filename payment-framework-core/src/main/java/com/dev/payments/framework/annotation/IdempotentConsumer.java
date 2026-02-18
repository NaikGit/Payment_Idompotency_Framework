package com.dev.payments.framework.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as an idempotent message consumer.
 * 
 * The framework will automatically:
 * 1. Extract the idempotency key from incoming messages
 * 2. Check if the message has already been processed
 * 3. Create state tracking and outbox entries atomically
 * 4. Handle message acknowledgment after successful persistence
 * 5. Process outbox entries asynchronously
 * 
 * Example usage:
 * <pre>
 * {@code
 * @IdempotentConsumer(
 *     idempotencyKey = "#{payload.transactionId}",
 *     destination = "CENTRAL_LEDGER"
 * )
 * public class PaymentEventHandler implements PaymentMessageHandler<PaymentEvent> {
 *     
 *     @Override
 *     public OutboxPayload processPayment(PaymentEvent event) {
 *         return OutboxPayload.builder()
 *             .endpoint("/api/v1/payments")
 *             .body(event)
 *             .build();
 *     }
 * }
 * }
 * </pre>
 * 
 * SpEL Expression Support:
 * - #{payload.field} - Access fields on the message payload
 * - #{headers['headerName']} - Access message headers
 * - #{payload.nested.field} - Access nested fields
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface IdempotentConsumer {
    
    /**
     * SpEL expression to extract the idempotency key from the message.
     * The expression is evaluated against a context containing:
     * - payload: The deserialized message body
     * - headers: Map of message headers
     * 
     * Examples:
     * - "#{payload.transactionId}"
     * - "#{payload.header.messageId}"
     * - "#{headers['correlationId']}"
     * - "#{payload.accountId + '-' + payload.transactionId}"
     */
    String idempotencyKey();
    
    /**
     * Identifier for the destination system.
     * Used for routing outbox entries and metrics.
     * 
     * Examples: "CENTRAL_LEDGER", "CORE_BANKING", "NOTIFICATION_SERVICE"
     */
    String destination();
    
    /**
     * Source identifier (queue/topic name).
     * If not specified, will be auto-detected from the message listener.
     */
    String source() default "";
    
    /**
     * Maximum number of delivery retries before marking as failed.
     * Default: 5
     */
    int maxRetries() default 5;
    
    /**
     * Base delay in seconds for exponential backoff.
     * Default: 30 seconds
     */
    int retryBaseDelaySeconds() default 30;
    
    /**
     * Maximum delay in seconds for exponential backoff.
     * Default: 3600 seconds (1 hour)
     */
    int retryMaxDelaySeconds() default 3600;
    
    /**
     * Whether to skip processing if a duplicate is detected.
     * If true (default), duplicates are acknowledged and skipped silently.
     * If false, an exception is thrown for duplicates.
     */
    boolean skipDuplicates() default true;
    
    /**
     * Metric tags to add to all metrics emitted by this consumer.
     * Format: "key1=value1,key2=value2"
     */
    String metricTags() default "";
}
