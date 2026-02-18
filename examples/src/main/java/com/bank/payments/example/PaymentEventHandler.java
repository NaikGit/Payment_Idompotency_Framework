package com.dev.payments.example;

import com.dev.payments.framework.annotation.IdempotentConsumer;
import com.dev.payments.framework.handler.PaymentMessageHandler;
import com.dev.payments.framework.model.OutboxPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

/**
 * ============================================================================
 * EXAMPLE: How Teams Will Use the Payment Idempotency Framework
 * ============================================================================
 * 
 * This example shows the minimal code a team needs to write.
 * The framework handles everything else:
 * - Idempotency checking
 * - State management
 * - Outbox persistence
 * - Message acknowledgment
 * - Retry logic
 * - Metrics
 * 
 * BEFORE (without framework):
 * - 200+ lines of boilerplate
 * - Easy to make mistakes
 * - Inconsistent across teams
 * 
 * AFTER (with framework):
 * - ~30 lines of business logic
 * - Cannot make idempotency mistakes
 * - Consistent patterns everywhere
 */
@IdempotentConsumer(
    idempotencyKey = "#{payload.transactionId}",
    destination = "CENTRAL_LEDGER",
    maxRetries = 5,
    retryBaseDelaySeconds = 30
)
@Component
public class PaymentEventHandler implements PaymentMessageHandler<PaymentEvent> {
    
    private static final Logger log = LoggerFactory.getLogger(PaymentEventHandler.class);
    
    private final PaymentTransformer transformer;
    
    public PaymentEventHandler(PaymentTransformer transformer) {
        this.transformer = transformer;
    }
    
    /**
     * Process the payment and return what to send downstream.
     * 
     * This is the ONLY method teams need to implement.
     * Everything else is handled by the framework.
     */
    @Override
    public OutboxPayload processPayment(PaymentEvent event) throws Exception {
        log.info("Processing payment: {}", event.getTransactionId());
        
        // Transform to downstream format
        LedgerRequest ledgerRequest = transformer.toLedgerRequest(event);
        
        // Return the outbox payload
        // Framework will:
        // 1. Save this to outbox table (atomically with idempotency record)
        // 2. Acknowledge the MQ message
        // 3. Deliver asynchronously via OutboxProcessor
        // 4. Retry on failure with exponential backoff
        return OutboxPayload.builder()
                .endpoint("https://ledger.internal/api/v1/transactions")
                .method(HttpMethod.POST)
                .body(ledgerRequest)
                .addHeader("X-Correlation-Id", event.getCorrelationId())
                .addHeader("X-Source-System", "PAYMENT_GATEWAY")
                .timeoutSeconds(30)
                .build();
    }
    
    /**
     * Optional: Called when a duplicate message is detected.
     */
    @Override
    public void onDuplicateDetected(PaymentEvent event, String idempotencyKey) {
        log.info("Duplicate payment skipped: {} (key: {})", 
                event.getTransactionId(), idempotencyKey);
        // Could emit custom metrics here
    }
    
    /**
     * Optional: Called when all retries are exhausted.
     */
    @Override
    public void onPermanentFailure(PaymentEvent event, String idempotencyKey, String lastError) {
        log.error("Payment permanently failed: {} - {}", 
                event.getTransactionId(), lastError);
        // Could trigger alert, create incident ticket, etc.
    }
    
    /**
     * Optional: Called on successful delivery.
     */
    @Override
    public void onDeliverySuccess(PaymentEvent event, String idempotencyKey) {
        log.info("Payment delivered successfully: {}", event.getTransactionId());
    }
    
    /**
     * Optional: Validate the payload before processing.
     */
    @Override
    public boolean validate(PaymentEvent event) {
        return event != null && 
               event.getTransactionId() != null && 
               event.getAmount() != null;
    }
}

// ============================================================================
// Supporting classes (would be in separate files in real project)
// ============================================================================

/**
 * Example incoming payment event (from MQ/Kafka).
 */
class PaymentEvent {
    private String transactionId;
    private String correlationId;
    private String sourceAccount;
    private String destinationAccount;
    private java.math.BigDecimal amount;
    private String currency;
    private java.time.Instant timestamp;
    
    // Getters and setters
    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }
    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }
    public String getSourceAccount() { return sourceAccount; }
    public void setSourceAccount(String sourceAccount) { this.sourceAccount = sourceAccount; }
    public String getDestinationAccount() { return destinationAccount; }
    public void setDestinationAccount(String destinationAccount) { this.destinationAccount = destinationAccount; }
    public java.math.BigDecimal getAmount() { return amount; }
    public void setAmount(java.math.BigDecimal amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public java.time.Instant getTimestamp() { return timestamp; }
    public void setTimestamp(java.time.Instant timestamp) { this.timestamp = timestamp; }
}

/**
 * Example outgoing ledger request.
 */
class LedgerRequest {
    private String transactionReference;
    private String debitAccount;
    private String creditAccount;
    private java.math.BigDecimal amount;
    private String currency;
    private String narrative;
    
    // Getters and setters
    public String getTransactionReference() { return transactionReference; }
    public void setTransactionReference(String ref) { this.transactionReference = ref; }
    public String getDebitAccount() { return debitAccount; }
    public void setDebitAccount(String account) { this.debitAccount = account; }
    public String getCreditAccount() { return creditAccount; }
    public void setCreditAccount(String account) { this.creditAccount = account; }
    public java.math.BigDecimal getAmount() { return amount; }
    public void setAmount(java.math.BigDecimal amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getNarrative() { return narrative; }
    public void setNarrative(String narrative) { this.narrative = narrative; }
}

/**
 * Example transformer service.
 */
@Component
class PaymentTransformer {
    public LedgerRequest toLedgerRequest(PaymentEvent event) {
        LedgerRequest request = new LedgerRequest();
        request.setTransactionReference(event.getTransactionId());
        request.setDebitAccount(event.getSourceAccount());
        request.setCreditAccount(event.getDestinationAccount());
        request.setAmount(event.getAmount());
        request.setCurrency(event.getCurrency());
        request.setNarrative("Payment: " + event.getTransactionId());
        return request;
    }
}
