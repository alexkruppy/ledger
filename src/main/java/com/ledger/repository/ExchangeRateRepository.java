package com.ledger.repository;

import com.ledger.model.ExchangeRate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExchangeRateRepository extends JpaRepository<ExchangeRate, Long> {

    Optional<ExchangeRate> findByBaseCurrencyAndQuoteCurrency(String baseCurrency, String quoteCurrency);

    List<ExchangeRate> findByBaseCurrencyOrderByQuoteCurrency(String baseCurrency);
}
