package com.example.payments.model;

import static com.example.payments.model.PaymentStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PaymentStatusTest {

    @Test
    void allowedTransitionsMatchTheSpec() {
        assertThat(PDNG.allowedNext()).containsExactlyInAnyOrder(ACCP, RJCT, CANC);
        assertThat(ACCP.allowedNext()).containsExactlyInAnyOrder(ACSC, CANC);
        assertThat(ACSC.allowedNext()).isEmpty();
        assertThat(RJCT.allowedNext()).isEmpty();
        assertThat(CANC.allowedNext()).isEmpty();
    }

    @Test
    void finalStates() {
        assertThat(ACSC.isFinal()).isTrue();
        assertThat(RJCT.isFinal()).isTrue();
        assertThat(CANC.isFinal()).isTrue();
        assertThat(PDNG.isFinal()).isFalse();
        assertThat(ACCP.isFinal()).isFalse();
    }

    @Test
    void cancellableOnlyWhileOpen() {
        assertThat(PDNG.isCancellable()).isTrue();
        assertThat(ACCP.isCancellable()).isTrue();
        assertThat(ACSC.isCancellable()).isFalse();
        assertThat(RJCT.isCancellable()).isFalse();
        assertThat(CANC.isCancellable()).isFalse();
    }
}
