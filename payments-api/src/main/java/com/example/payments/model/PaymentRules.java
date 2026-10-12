package com.example.payments.model;

import com.example.payments.error.InvalidAmountException;
import com.example.payments.error.UnsupportedCurrencyException;
import java.math.BigDecimal;
import java.util.Set;

/**
 * Business rules that depend on configuration: the currency allowlist and the
 * maximum amount. Plain Java, so it is easy to unit test.
 */
public final class PaymentRules {

    private final Set<String> supportedCurrencies;
    private final BigDecimal maxAmount;

    public PaymentRules(Set<String> supportedCurrencies, BigDecimal maxAmount) {
        this.supportedCurrencies = Set.copyOf(supportedCurrencies);
        this.maxAmount = maxAmount;
    }

    /** Throws if the currency is not on the allowlist or the amount is above the maximum. */
    public void check(Money money) {
        if (!supportedCurrencies.contains(money.currencyCode())) {
            throw new UnsupportedCurrencyException("Currency " + money.currencyCode() + " is not supported");
        }
        BigDecimal amount = new BigDecimal(money.toAmountString());
        if (amount.compareTo(maxAmount) > 0) {
            throw new InvalidAmountException("Amount must not be more than " + maxAmount.toPlainString());
        }
    }

    public Set<String> supportedCurrencies() {
        return supportedCurrencies;
    }
}
