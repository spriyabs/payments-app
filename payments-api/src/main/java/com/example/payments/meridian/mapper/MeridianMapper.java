package com.example.payments.meridian.mapper;

import com.example.payments.error.UnsupportedCurrencyException;
import com.example.payments.meridian.model.ProviderAmount;
import com.example.payments.model.Money;

/**
 * The one place that knows Meridian's wire format. Its schema forces exactly two
 * decimals, so currencies with 0 (JPY) or 3 (KWD) decimals are refused here.
 * Lift the restriction here when Meridian says how it expects those.
 */
public final class MeridianMapper {

    private static final int MERIDIAN_FRACTION_DIGITS = 2;

    private MeridianMapper() {
    }

    public static boolean isSupported(Money money) {
        return money.currency().getDefaultFractionDigits() == MERIDIAN_FRACTION_DIGITS;
    }

    public static ProviderAmount toProviderAmount(Money money) {
        if (!isSupported(money)) {
            throw new UnsupportedCurrencyException("Currency " + money.currencyCode() + " is not supported");
        }
        return new ProviderAmount(money.toAmountString(), money.currencyCode());
    }
}
