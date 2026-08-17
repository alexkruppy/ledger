package com.ledger.service;

import com.ledger.config.LedgerProperties;
import com.ledger.model.ExchangeRate;
import com.ledger.repository.ExchangeRateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FxServiceTest {

    @Mock
    private ExchangeRateRepository exchangeRateRepository;

    private FxService fxService;

    @BeforeEach
    void setUp() {
        when(exchangeRateRepository.findByBaseCurrencyOrderByQuoteCurrency("EUR")).thenReturn(List.of(
                rate("EUR", "USD", "1.10"),
                rate("EUR", "GBP", "0.85")));
        LedgerProperties properties = new LedgerProperties(
                null, null, null, null, null, null, new LedgerProperties.Fx("EUR"), null);
        fxService = new FxService(exchangeRateRepository, properties);
    }

    @Test
    void sameCurrencyRateIsOne() {
        assertThat(fxService.rate("USD", "USD")).isEqualByComparingTo("1");
    }

    @Test
    void directPivotRate() {
        assertThat(fxService.rate("EUR", "USD")).isEqualByComparingTo("1.10");
    }

    @Test
    void crossCurrencyViaPivot() {
        // 1 USD = 0.85 / 1.10 GBP
        assertThat(fxService.rate("USD", "GBP"))
                .isEqualByComparingTo(BigDecimal.valueOf(0.85).divide(BigDecimal.valueOf(1.10), 12, RoundingMode.HALF_UP));
    }

    @Test
    void conversionAppliesRate() {
        BigDecimal converted = fxService.convert(new BigDecimal("100.00"), "EUR", "USD");
        assertThat(converted).isEqualByComparingTo(new BigDecimal("110.00000000"));
    }

    @Test
    void supportedCurrenciesContainAll() {
        assertThat(fxService.supportedCurrencies()).contains("USD", "GBP", "EUR");
    }

    private ExchangeRate rate(String base, String quote, String value) {
        ExchangeRate r = new ExchangeRate();
        r.setBaseCurrency(base);
        r.setQuoteCurrency(quote);
        r.setRate(new BigDecimal(value));
        return r;
    }
}
