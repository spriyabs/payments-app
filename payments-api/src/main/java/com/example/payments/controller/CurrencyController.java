package com.example.payments.controller;

import com.example.payments.model.Money;
import com.example.payments.model.PaymentRules;
import com.example.payments.model.dto.CurrencyDto;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/currencies")
public class CurrencyController {

    private final PaymentRules rules;

    public CurrencyController(PaymentRules rules) {
        this.rules = rules;
    }

    /** Currencies the UI can offer: on the allowlist AND usable with Meridian (2 decimals). */
    @GetMapping
    public List<CurrencyDto> list() {
        return rules.supportedCurrencies().stream()
                .sorted()
                .map(Money::currencyOf)
                .filter(currency -> currency.getDefaultFractionDigits() == 2)
                .map(currency -> new CurrencyDto(currency.getCurrencyCode(), currency.getDefaultFractionDigits()))
                .toList();
    }
}
