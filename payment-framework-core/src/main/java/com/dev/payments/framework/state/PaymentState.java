package com.dev.payments.framework.state;

/**
 * Represents the lifecycle states of a payment event.
 * 
 * State Machine:
 * 
 *     [NEW MESSAGE] 
 *          │
 *          ▼
 *     ┌─────────┐
 *     │ RECEIVED│  (Message received, idempotency check passed)
 *     └────┬────┘
 *          │
 *          ▼
 *    ┌──────────┐
 *    │PROCESSING│  (Outbox entry created, ready for delivery)
 *    └────┬─────┘
 *          │
 *     ┌────┴────┐
 *     │         │
 *     ▼         ▼
 * ┌───────┐ ┌──────┐
 * │SENDING│ │FAILED│◄──┐
 * └───┬───┘ └──┬───┘   │
 *     │        │       │
 *     │        └───────┘ (Retry if count < max)
 *     │
 *     ▼
 * ┌─────────┐
 * │COMPLETED│  (Terminal - success)
 * └─────────┘
 * 
 * Invalid Transitions (Duplicates):
 * - Any state → RECEIVED (except NEW)
 * - COMPLETED → Any state
 * - SENDING → RECEIVED or PROCESSING
 */
public enum PaymentState {
    
    /**
     * Initial state when message is first received and passes idempotency check.
     * Transition: RECEIVED → PROCESSING (always immediate)
     */
    RECEIVED("Message received, pending processing"),
    
    /**
     * Outbox entry created, waiting for processor to pick up.
     * Transition: PROCESSING → SENDING (when processor picks up)
     * Transition: PROCESSING → FAILED (if outbox creation fails)
     */
    PROCESSING("Processing initiated, outbox entry created"),
    
    /**
     * Outbox processor is attempting delivery to downstream.
     * Transition: SENDING → COMPLETED (on success)
     * Transition: SENDING → FAILED (on failure)
     */
    SENDING("Delivery in progress"),
    
    /**
     * Terminal success state.
     * No transitions allowed from this state.
     */
    COMPLETED("Successfully delivered to downstream"),
    
    /**
     * Failed state - may be retried if retry count < max.
     * Transition: FAILED → SENDING (on retry)
     * Transition: FAILED → COMPLETED (manual resolution)
     */
    FAILED("Delivery failed");
    
    private final String description;
    
    PaymentState(String description) {
        this.description = description;
    }
    
    public String getDescription() {
        return description;
    }
    
    /**
     * Check if this state is terminal (no further transitions allowed).
     */
    public boolean isTerminal() {
        return this == COMPLETED;
    }
    
    /**
     * Check if this state allows retry.
     */
    public boolean isRetryable() {
        return this == FAILED;
    }
    
    /**
     * Check if transition from this state to target state is valid.
     */
    public boolean canTransitionTo(PaymentState target) {
        return switch (this) {
            case RECEIVED -> target == PROCESSING;
            case PROCESSING -> target == SENDING || target == FAILED;
            case SENDING -> target == COMPLETED || target == FAILED;
            case FAILED -> target == SENDING || target == COMPLETED;
            case COMPLETED -> false; // Terminal state
        };
    }
    
    /**
     * Validate and execute state transition.
     * @throws InvalidStateTransitionException if transition is not allowed
     */
    public PaymentState transitionTo(PaymentState target) {
        if (!canTransitionTo(target)) {
            throw new InvalidStateTransitionException(this, target);
        }
        return target;
    }
}
