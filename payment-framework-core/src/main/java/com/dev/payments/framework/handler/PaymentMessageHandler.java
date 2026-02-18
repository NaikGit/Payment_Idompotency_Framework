package com.dev.payments.framework.handler;

import com.dev.payments.framework.model.OutboxPayload;

/**
 * Interface that teams implement to handle payment messages.
 * 
 * The framework handles all infrastructure concerns:
 * - Idempotency checking
 * - State management
 * - Outbox persistence
 * - Message acknowledgment
 * - Retry logic
 * - Metrics
 * 
 * Teams only need to implement the business logic in {@link #processPayment(Object)}.
 * 
 * Example:
 * <pre>
 * {@code
 * @IdempotentConsumer(
 *     idempotencyKey = "#{payload.transactionId}",
 *     destination = "CENTRAL_LEDGER"
 * )
 * @Component
 * public class PaymentEventHandler implements PaymentMessageHandler<PaymentEvent> {
 *     
 *     private final PaymentTransformer transformer;
 *     
 *     @Override
 *     public OutboxPayload processPayment(PaymentEvent event) {
 *         // Transform the event to the downstream format
 *         LedgerRequest request = transformer.toLedgerRequest(event);
 *         
 *         // Return the outbox payload - framework handles delivery
 *         return OutboxPayload.builder()
 *             .endpoint("https://ledger.internal/api/v1/transactions")
 *             .method(HttpMethod.POST)
 *             .body(request)
 *             .addHeader("X-Correlation-Id", event.getCorrelationId())
 *             .build();
 *     }
 *     
 *     @Override
 *     public void onDuplicateDetected(PaymentEvent event, String idempotencyKey) {
 *         // Optional: Log or handle duplicate detection
 *         log.info("Duplicate payment detected: {}", idempotencyKey);
 *     }
 * }
 * }
 * </pre>
 * 
 * @param <T> The type of the payment message
 */
public interface PaymentMessageHandler<T> {
    
    /**
     * Process the payment message and return the outbox payload.
     * 
     * This method is called AFTER the idempotency check passes.
     * The returned OutboxPayload will be persisted to the outbox table
     * within the same transaction as the idempotency record.
     * 
     * If this method throws an exception:
     * - The transaction is rolled back
     * - No idempotency record is created
     * - The message is NOT acknowledged (will be redelivered)
     * 
     * @param payload The deserialized message payload
     * @return OutboxPayload describing what to send downstream
     * @throws Exception if processing fails
     */
    OutboxPayload processPayment(T payload) throws Exception;
    
    /**
     * Called when a duplicate message is detected.
     * Default implementation does nothing.
     * Override to add logging or metrics.
     * 
     * @param payload The duplicate message payload
     * @param idempotencyKey The key that was already processed
     */
    default void onDuplicateDetected(T payload, String idempotencyKey) {
        // Default: do nothing
    }
    
    /**
     * Called when processing fails after all retries are exhausted.
     * Default implementation does nothing.
     * Override to add alerting or manual intervention triggers.
     * 
     * @param payload The failed message payload
     * @param idempotencyKey The idempotency key
     * @param lastError The last error that occurred
     */
    default void onPermanentFailure(T payload, String idempotencyKey, String lastError) {
        // Default: do nothing
    }
    
    /**
     * Called when delivery succeeds.
     * Default implementation does nothing.
     * Override to add post-processing logic.
     * 
     * @param payload The message payload
     * @param idempotencyKey The idempotency key
     */
    default void onDeliverySuccess(T payload, String idempotencyKey) {
        // Default: do nothing
    }
    
    /**
     * Validate the payload before processing.
     * Default implementation returns true (no validation).
     * Override to add validation logic.
     * 
     * @param payload The message payload to validate
     * @return true if valid, false to reject
     */
    default boolean validate(T payload) {
        return true;
    }
    
    /**
     * Get the class of the payload type.
     * Used for deserialization.
     * Default implementation uses reflection; override for better performance.
     * 
     * @return The payload class
     */
    @SuppressWarnings("unchecked")
    default Class<T> getPayloadType() {
        // This will be resolved via reflection in the framework
        return null;
    }
}
