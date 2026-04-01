package com.dev.payments.framework.state;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PaymentState")
class PaymentStateTest {

    // -------------------------------------------------------------------------
    // Valid transitions
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("given a valid state transition")
    class GivenValidTransition {

        @ParameterizedTest(name = "{0} → {1}")
        @MethodSource("com.dev.payments.framework.state.PaymentStateTest#validTransitions")
        @DisplayName("when transitionTo is called, then returns the target state")
        void whenTransitionTo_thenReturnsTargetState(PaymentState from, PaymentState to) {
            // When
            PaymentState result = from.transitionTo(to);

            // Then
            assertThat(result).isEqualTo(to);
        }

        @ParameterizedTest(name = "{0} → {1}")
        @MethodSource("com.dev.payments.framework.state.PaymentStateTest#validTransitions")
        @DisplayName("when canTransitionTo is called, then returns true")
        void whenCanTransitionTo_thenReturnsTrue(PaymentState from, PaymentState to) {
            assertThat(from.canTransitionTo(to)).isTrue();
        }
    }

    // -------------------------------------------------------------------------
    // Invalid transitions
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("given an invalid state transition")
    class GivenInvalidTransition {

        @ParameterizedTest(name = "{0} → {1}")
        @MethodSource("com.dev.payments.framework.state.PaymentStateTest#invalidTransitions")
        @DisplayName("when transitionTo is called, then throws InvalidStateTransitionException")
        void whenTransitionTo_thenThrowsInvalidStateTransitionException(PaymentState from, PaymentState to) {
            assertThatThrownBy(() -> from.transitionTo(to))
                .isInstanceOf(InvalidStateTransitionException.class);
        }

        @ParameterizedTest(name = "{0} → {1}")
        @MethodSource("com.dev.payments.framework.state.PaymentStateTest#invalidTransitions")
        @DisplayName("when canTransitionTo is called, then returns false")
        void whenCanTransitionTo_thenReturnsFalse(PaymentState from, PaymentState to) {
            assertThat(from.canTransitionTo(to)).isFalse();
        }
    }

    // -------------------------------------------------------------------------
    // COMPLETED is terminal
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("given the COMPLETED state (terminal)")
    class GivenCompletedState {

        @ParameterizedTest(name = "COMPLETED → {0}")
        @EnumSource(PaymentState.class)
        @DisplayName("when transitionTo any state, then throws InvalidStateTransitionException")
        void whenTransitionToAnyState_thenThrows(PaymentState target) {
            assertThatThrownBy(() -> PaymentState.COMPLETED.transitionTo(target))
                .isInstanceOf(InvalidStateTransitionException.class);
        }

        @Test
        @DisplayName("when isTerminal is called, then returns true")
        void whenIsTerminal_thenReturnsTrue() {
            assertThat(PaymentState.COMPLETED.isTerminal()).isTrue();
        }
    }

    // -------------------------------------------------------------------------
    // isTerminal
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("given non-terminal states")
    class GivenNonTerminalStates {

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = PaymentState.class, names = {"RECEIVED", "PROCESSING", "SENDING", "FAILED"})
        @DisplayName("when isTerminal is called, then returns false")
        void whenIsTerminal_thenReturnsFalse(PaymentState state) {
            assertThat(state.isTerminal()).isFalse();
        }
    }

    // -------------------------------------------------------------------------
    // isRetryable
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("given the FAILED state")
    class GivenFailedState {

        @Test
        @DisplayName("when isRetryable is called, then returns true")
        void whenIsRetryable_thenReturnsTrue() {
            assertThat(PaymentState.FAILED.isRetryable()).isTrue();
        }
    }

    @Nested
    @DisplayName("given non-retryable states")
    class GivenNonRetryableStates {

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = PaymentState.class, names = {"RECEIVED", "PROCESSING", "SENDING", "COMPLETED"})
        @DisplayName("when isRetryable is called, then returns false")
        void whenIsRetryable_thenReturnsFalse(PaymentState state) {
            assertThat(state.isRetryable()).isFalse();
        }
    }

    // -------------------------------------------------------------------------
    // InvalidStateTransitionException carries context
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("given an invalid transition from RECEIVED to COMPLETED")
    class GivenInvalidTransitionException {

        @Test
        @DisplayName("when transitionTo throws, then exception carries the from and to states")
        void whenTransitionToThrows_thenExceptionCarriesStates() {
            // When
            InvalidStateTransitionException ex = null;
            try {
                PaymentState.RECEIVED.transitionTo(PaymentState.COMPLETED);
            } catch (InvalidStateTransitionException e) {
                ex = e;
            }

            // Then
            assertThat(ex).isNotNull();
            assertThat(ex.getFromState()).isEqualTo(PaymentState.RECEIVED);
            assertThat(ex.getToState()).isEqualTo(PaymentState.COMPLETED);
        }
    }

    // -------------------------------------------------------------------------
    // Data sources
    // -------------------------------------------------------------------------

    static Stream<Arguments> validTransitions() {
        return Stream.of(
            Arguments.of(PaymentState.RECEIVED,    PaymentState.PROCESSING),
            Arguments.of(PaymentState.PROCESSING,  PaymentState.SENDING),
            Arguments.of(PaymentState.PROCESSING,  PaymentState.FAILED),
            Arguments.of(PaymentState.SENDING,     PaymentState.COMPLETED),
            Arguments.of(PaymentState.SENDING,     PaymentState.FAILED),
            Arguments.of(PaymentState.FAILED,      PaymentState.SENDING),
            Arguments.of(PaymentState.FAILED,      PaymentState.COMPLETED)
        );
    }

    static Stream<Arguments> invalidTransitions() {
        return Stream.of(
            Arguments.of(PaymentState.RECEIVED,   PaymentState.SENDING),
            Arguments.of(PaymentState.RECEIVED,   PaymentState.FAILED),
            Arguments.of(PaymentState.PROCESSING, PaymentState.RECEIVED),
            Arguments.of(PaymentState.PROCESSING, PaymentState.COMPLETED),
            Arguments.of(PaymentState.SENDING,    PaymentState.RECEIVED),
            Arguments.of(PaymentState.SENDING,    PaymentState.PROCESSING),
            Arguments.of(PaymentState.FAILED,     PaymentState.RECEIVED),
            Arguments.of(PaymentState.FAILED,     PaymentState.PROCESSING)
        );
    }
}
