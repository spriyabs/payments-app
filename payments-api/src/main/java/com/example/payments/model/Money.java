package com.example.payments.model;

import com.example.payments.error.InvalidAmountException;
import com.example.payments.error.UnsupportedCurrencyException;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.regex.Pattern;

/**
 * An amount in minor units (pence, cents) plus a currency. Never a double.
 *
 * <p>Parsing is strict: bad input throws instead of rounding. Which currencies we
 * accept (allowlist, the 2-decimal restriction) is decided elsewhere; this class
 * handles any ISO 4217 currency using its own number of decimal places.
 */
public record Money(long minorUnits, Currency currency) {

    // Plain decimal only: no sign, no exponent, no spaces, no thousands separators.
    private static final Pattern PLAIN_DECIMAL = Pattern.compile("\\d+(\\.\\d+)?");

    public Money {
        if (currency == null) {
            throw new IllegalArgumentException("currency is required");
        }
        if (minorUnits <= 0) {
            throw new InvalidAmountException("Amount must be greater than zero");
        }
    }

    /**
     * Parses a wire amount such as {@code "125.50"}. Accepts at most the currency's
     * number of decimals (so "125.5" is fine for GBP, "125.505" is not).
     */
    public static Money parse(String amount, String currencyCode) {
        Currency currency = currencyOf(currencyCode);
        if (amount == null || !PLAIN_DECIMAL.matcher(amount).matches()) {
            throw new InvalidAmountException("Amount must be a plain decimal number such as 125.50");
        }
        int digits = currency.getDefaultFractionDigits();
        if (digits < 0) {
            throw new UnsupportedCurrencyException("Currency has no minor unit: " + currency.getCurrencyCode());
        }
        try {
            long minor = new BigDecimal(amount).movePointRight(digits).longValueExact();
            return new Money(minor, currency);
        } catch (ArithmeticException e) {
            // Too many decimals (would need rounding) or too large for a long.
            throw new InvalidAmountException(
                    "Amount has more than " + digits + " decimal places or is too large");
        }
    }

    public static Currency currencyOf(String code) {
        if (code == null || !code.matches("[A-Z]{3}")) {
            throw new UnsupportedCurrencyException("Currency must be a 3-letter ISO 4217 code");
        }
        try {
            return Currency.getInstance(code);
        } catch (IllegalArgumentException e) {
            throw new UnsupportedCurrencyException("Unknown currency: " + code);
        }
    }

    /** Wire format with exactly the currency's number of decimals, e.g. {@code "125.50"}. */
    public String toAmountString() {
        return BigDecimal.valueOf(minorUnits, currency.getDefaultFractionDigits()).toPlainString();
    }

    public String currencyCode() {
        return currency.getCurrencyCode();
    }

    @Override
    public String toString() {
        return toAmountString() + " " + currencyCode();
    }
}
