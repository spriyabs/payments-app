package com.example.payments.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.payments.model.Money;
import org.junit.jupiter.api.Test;

class RequestHashTest {

    @Test
    void sameRequestGivesTheSameHash() {
        assertThat(PaymentService.requestHash("tok_1", Money.parse("125.50", "GBP")))
                .isEqualTo(PaymentService.requestHash("tok_1", Money.parse("125.5", "GBP")));
    }

    @Test
    void anyDifferenceChangesTheHash() {
        String base = PaymentService.requestHash("tok_1", Money.parse("125.50", "GBP"));
        assertThat(PaymentService.requestHash("tok_2", Money.parse("125.50", "GBP"))).isNotEqualTo(base);
        assertThat(PaymentService.requestHash("tok_1", Money.parse("125.51", "GBP"))).isNotEqualTo(base);
        assertThat(PaymentService.requestHash("tok_1", Money.parse("125.50", "EUR"))).isNotEqualTo(base);
    }

    @Test
    void hashIs64HexCharactersAndDoesNotContainTheToken() {
        String hash = PaymentService.requestHash("tok_secret", Money.parse("1.00", "GBP"));
        assertThat(hash).matches("[0-9a-f]{64}").doesNotContain("tok_secret");
    }
}
