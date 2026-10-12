package com.example.payments.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.payments.error.InvalidAmountException;
import com.example.payments.error.UnsupportedCurrencyException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyTest {

    @Test
    void parsesToMinorUnits() {
        Money money = Money.parse("125.50", "GBP");
        assertThat(money.minorUnits()).isEqualTo(12550);
        assertThat(money.currencyCode()).isEqualTo("GBP");
    }

    @Test
    void acceptsFewerDecimalsAndWholeNumbers() {
        assertThat(Money.parse("125.5", "EUR").minorUnits()).isEqualTo(12550);
        assertThat(Money.parse("10", "USD").minorUnits()).isEqualTo(1000);
    }

    @Test
    void wireFormatHasExactlyTheCurrencyDecimals() {
        assertThat(Money.parse("10", "GBP").toAmountString()).isEqualTo("10.00");
        assertThat(Money.parse("0.05", "GBP").toAmountString()).isEqualTo("0.05");
        assertThat(Money.parse("125.5", "GBP").toAmountString()).isEqualTo("125.50");
    }

    @Test
    void usesTheCurrencysOwnDecimalPlaces() {
        assertThat(Money.parse("500", "JPY").minorUnits()).isEqualTo(500);
        assertThat(Money.parse("1.234", "KWD").minorUnits()).isEqualTo(1234);
        assertThat(Money.parse("500", "JPY").toAmountString()).isEqualTo("500");
        assertThat(Money.parse("1.234", "KWD").toAmountString()).isEqualTo("1.234");
    }

    @Test
    void rejectsTooManyDecimalsInsteadOfRounding() {
        assertThatThrownBy(() -> Money.parse("1.005", "GBP")).isInstanceOf(InvalidAmountException.class);
        assertThatThrownBy(() -> Money.parse("1.5", "JPY")).isInstanceOf(InvalidAmountException.class);
    }

    @Test
    void rejectsAmountTooLargeForALong() {
        assertThatThrownBy(() -> Money.parse("99999999999999999999", "GBP"))
                .isInstanceOf(InvalidAmountException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-5.00", "+5.00", "1e3", "1,000.00", " 5.00", "5.00 ", "", "abc", ".5", "5."})
    void rejectsMalformedOrNonPositiveAmounts(String amount) {
        assertThatThrownBy(() -> Money.parse(amount, "GBP")).isInstanceOf(InvalidAmountException.class);
    }

    @Test
    void rejectsNullAmount() {
        assertThatThrownBy(() -> Money.parse(null, "GBP")).isInstanceOf(InvalidAmountException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "gbp", "GB", "GBPP", "XXY", "ZZZ"})
    void rejectsBadCurrencyCodes(String code) {
        assertThatThrownBy(() -> Money.parse("10.00", code)).isInstanceOf(UnsupportedCurrencyException.class);
    }

    @Test
    void rejectsNullCurrency() {
        assertThatThrownBy(() -> Money.parse("10.00", null)).isInstanceOf(UnsupportedCurrencyException.class);
    }

    @Test
    void rejectsCurrenciesWithoutAMinorUnit() {
        // XXX (no currency) has fraction digits -1
        assertThatThrownBy(() -> Money.parse("10", "XXX")).isInstanceOf(UnsupportedCurrencyException.class);
    }

    @Test
    void constructorRejectsNonPositive() {
        var gbp = Money.currencyOf("GBP");
        assertThatThrownBy(() -> new Money(0, gbp)).isInstanceOf(InvalidAmountException.class);
        assertThatThrownBy(() -> new Money(-1, gbp)).isInstanceOf(InvalidAmountException.class);
    }

    @Test
    void equalityIsByValue() {
        assertThat(Money.parse("1.50", "GBP")).isEqualTo(Money.parse("1.5", "GBP"));
        assertThat(Money.parse("1.50", "GBP")).isNotEqualTo(Money.parse("1.50", "EUR"));
    }

    @Test
    void toStringIsReadable() {
        assertThat(Money.parse("125.5", "GBP")).hasToString("125.50 GBP");
    }
}
