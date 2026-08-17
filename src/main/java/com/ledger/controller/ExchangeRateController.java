package com.ledger.controller;

import com.ledger.dto.ExchangeRateDto;
import com.ledger.service.ExchangeRateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/exchange-rates")
@Tag(name = "Exchange Rates", description = "Public currency rates (EUR pivot)")
public class ExchangeRateController {

    private final ExchangeRateService exchangeRateService;

    public ExchangeRateController(ExchangeRateService exchangeRateService) {
        this.exchangeRateService = exchangeRateService;
    }

    @GetMapping
    @Operation(summary = "List rates for a base currency (default EUR)")
    public ResponseEntity<List<ExchangeRateDto>> list(@RequestParam(defaultValue = "EUR") String base) {
        return ResponseEntity.ok(exchangeRateService.list(base.toUpperCase()));
    }
}
