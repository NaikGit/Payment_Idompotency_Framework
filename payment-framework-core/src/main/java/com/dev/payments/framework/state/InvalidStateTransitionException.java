package com.dev.payments.framework.state;

/**
 * Exception thrown when an invalid state transition is attempted.
 * This includes duplicate payment attempts (trying to transition from
 * an already-processed state).
 */
public class InvalidStateTransitionException extends RuntimeException {
    
    private final PaymentState fromState;
    private final PaymentState toState;
    
    public InvalidStateTransitionException(PaymentState fromState, PaymentState toState) {
        super(String.format("Invalid state transition: %s → %s", fromState, toState));
        this.fromState = fromState;
        this.toState = toState;
    }
    
    public InvalidStateTransitionException(PaymentState fromState, PaymentState toState, String reason) {
        super(String.format("Invalid state transition: %s → %s. Reason: %s", fromState, toState, reason));
        this.fromState = fromState;
        this.toState = toState;
    }
    
    public PaymentState getFromState() {
        return fromState;
    }
    
    public PaymentState getToState() {
        return toState;
    }
    
    /**
     * Check if this exception represents a duplicate payment attempt.
     */
    public boolean isDuplicateAttempt() {
        // If we're trying to move to RECEIVED or PROCESSING from a state
        // that shouldn't allow it, it's likely a duplicate
        return toState == PaymentState.RECEIVED || 
               (toState == PaymentState.PROCESSING && fromState != PaymentState.RECEIVED);
    }
}
