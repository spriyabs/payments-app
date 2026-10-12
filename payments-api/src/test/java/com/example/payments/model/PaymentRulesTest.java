package com.example.payments.model;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.payments.error.InvalidAmountException;
import com.example.payments.error.UnsupportedCurrencyException;
import java.math.BigDecimal;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PaymentRulesTest {

    private final PaymentRules rules = new PaymentRules(Set.of("GBP", "EUR"), new BigDecimal("10000.00"));

    @Test
    void acceptsSupportedCurrencyUpToTheMaximum() {
        assertThatCode(() -> rules.check(Money.parse("125.50", "GBP"))).doesNotThrowAnyException();
        assertThatCode(() -> rules.check(Money.parse("10000.00", "EUR"))).doesNotThrowAnyException();
    }

    @Test
    void rejectsAmountAboveTheMaximum() {
        assertThatThrownBy(() -> rules.check(Money.parse("10000.01", "GBP")))
                .isInstanceOf(InvalidAmountException.class);
    }

    @Test
    void rejectsCurrencyNotOnTheAllowlist() {
        assertThatThrownBy(() -> rules.check(Money.parse("1.00", "USD")))
                .isInstanceOf(UnsupportedCurrencyException.class)
                .hasMessageContaining("USD");
    }
}
