package com.ledger.service;

import com.ledger.dto.ExchangeRateDto;
import com.ledger.dto.ExchangeRateUpdateRequest;
import com.ledger.exception.ConflictException;
import com.ledger.exception.ResourceNotFoundException;
import com.ledger.model.ExchangeRate;
import com.ledger.repository.ExchangeRateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ExchangeRateService {

    private final ExchangeRateRepository exchangeRateRepository;
    private final FxService fxService;

    public ExchangeRateService(ExchangeRateRepository exchangeRateRepository, FxService fxService) {
        this.exchangeRateRepository = exchangeRateRepository;
        this.fxService = fxService;
    }

    @Transactional(readOnly = true)
    public List<ExchangeRateDto> list(String baseCurrency) {
        return exchangeRateRepository.findByBaseCurrencyOrderByQuoteCurrency(baseCurrency).stream()
                .map(ExchangeRateDto::from)
                .toList();
    }

    @Transactional
    public ExchangeRateDto upsert(ExchangeRateUpdateRequest request) {
        String base = request.baseCurrency().toUpperCase();
        String quote = request.quoteCurrency().toUpperCase();
        if (base.equals(quote)) {
            throw new ConflictException("Base and quote currencies must differ");
        }
        ExchangeRate rate = exchangeRateRepository.findByBaseCurrencyAndQuoteCurrency(base, quote)
                .orElseGet(() -> {
                    ExchangeRate r = new ExchangeRate();
                    r.setBaseCurrency(base);
                    r.setQuoteCurrency(quote);
                    return r;
                });
        rate.setRate(request.rate());
        exchangeRateRepository.save(rate);
        fxService.refresh();
        return ExchangeRateDto.from(rate);
    }

    @Transactional
    public void delete(String baseCurrency, String quoteCurrency) {
        ExchangeRate rate = exchangeRateRepository
                .findByBaseCurrencyAndQuoteCurrency(baseCurrency.toUpperCase(), quoteCurrency.toUpperCase())
                .orElseThrow(() -> new ResourceNotFoundException("Exchange rate not found"));
        exchangeRateRepository.delete(rate);
        fxService.refresh();
    }
}
