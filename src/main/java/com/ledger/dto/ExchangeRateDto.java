package com.ledger.dto;

import com.ledger.model.ExchangeRate;

import java.math.BigDecimal;
import java.time.Instant;

public record ExchangeRateDto(String baseCurrency, String quoteCurrency, BigDecimal rate, Instant updatedAt) {

    public static ExchangeRateDto from(ExchangeRate r) {
        return new ExchangeRateDto(r.getBaseCurrency(), r.getQuoteCurrency(), r.getRate(), r.getUpdatedAt());
    }
}
