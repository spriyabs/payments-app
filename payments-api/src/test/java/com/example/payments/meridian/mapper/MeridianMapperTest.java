package com.example.payments.meridian.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.payments.error.UnsupportedCurrencyException;
import com.example.payments.meridian.model.ProviderAmount;
import com.example.payments.model.Money;
import org.junit.jupiter.api.Test;

class MeridianMapperTest {

    @Test
    void mapsTwoDecimalCurrenciesToTheWireFormat() {
        ProviderAmount amount = MeridianMapper.toProviderAmount(Money.parse("125.5", "GBP"));
        assertThat(amount.value()).isEqualTo("125.50");
        assertThat(amount.currency()).isEqualTo("GBP");
    }

    @Test
    void refusesCurrenciesWithZeroDecimals() {
        assertThatThrownBy(() -> MeridianMapper.toProviderAmount(Money.parse("500", "JPY")))
                .isInstanceOf(UnsupportedCurrencyException.class)
                .hasMessageContaining("JPY");
    }

    @Test
    void refusesCurrenciesWithThreeDecimals() {
        assertThatThrownBy(() -> MeridianMapper.toProviderAmount(Money.parse("1.234", "KWD")))
                .isInstanceOf(UnsupportedCurrencyException.class);
    }
}
