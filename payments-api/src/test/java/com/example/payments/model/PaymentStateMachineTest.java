package com.example.payments.model;

import static com.example.payments.model.PaymentStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class PaymentStateMachineTest {

    @Test
    void validTransitionIsApplied() {
        var result = PaymentStateMachine.transition(PDNG, ACCP);
        assertThat(result.applied()).isTrue();
        assertThat(result.status()).isEqualTo(ACCP);
    }

    @Test
    void lateAcceptAfterCancelNeverRevertsCancelled() {
        var result = PaymentStateMachine.transition(CANC, ACCP);
        assertThat(result.applied()).isFalse();
        assertThat(result.status()).isEqualTo(CANC);
    }

    @Test
    void duplicateStatusIsIgnored() {
        var result = PaymentStateMachine.transition(ACCP, ACCP);
        assertThat(result.applied()).isFalse();
        assertThat(result.status()).isEqualTo(ACCP);
    }

    @Test
    void cannotSkipAuthorisation() {
        assertThat(PaymentStateMachine.transition(PDNG, ACSC).applied()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"ACSC", "RJCT", "CANC"})
    void finalStatesNeverChange(PaymentStatus finalStatus) {
        for (PaymentStatus requested : PaymentStatus.values()) {
            var result = PaymentStateMachine.transition(finalStatus, requested);
            assertThat(result.applied()).isFalse();
            assertThat(result.status()).isEqualTo(finalStatus);
        }
    }

    @Test
    void nullsAreRejected() {
        assertThatThrownBy(() -> PaymentStateMachine.transition(null, ACCP))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaymentStateMachine.transition(PDNG, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
