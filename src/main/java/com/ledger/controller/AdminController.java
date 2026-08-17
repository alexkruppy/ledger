package com.ledger.controller;

import com.ledger.dto.ExchangeRateDto;
import com.ledger.dto.ExchangeRateUpdateRequest;
import com.ledger.dto.PaymentDto;
import com.ledger.dto.TransferDto;
import com.ledger.service.ExchangeRateService;
import com.ledger.service.PaymentSagaService;
import com.ledger.service.TransferService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
@Tag(name = "Admin", description = "Admin operations: FX rates management and audit views")
public class AdminController {

    private final ExchangeRateService exchangeRateService;
    private final TransferService transferService;
    private final PaymentSagaService paymentSagaService;

    public AdminController(ExchangeRateService exchangeRateService,
                           TransferService transferService,
                           PaymentSagaService paymentSagaService) {
        this.exchangeRateService = exchangeRateService;
        this.transferService = transferService;
        this.paymentSagaService = paymentSagaService;
    }

    @PutMapping("/exchange-rates")
    @Operation(summary = "Create or update an exchange rate")
    public ResponseEntity<ExchangeRateDto> upsertRate(@Valid @RequestBody ExchangeRateUpdateRequest request) {
        return ResponseEntity.ok(exchangeRateService.upsert(request));
    }

    @DeleteMapping("/exchange-rates/{base}/{quote}")
    @Operation(summary = "Delete an exchange rate")
    public ResponseEntity<Void> deleteRate(@PathVariable String base, @PathVariable String quote) {
        exchangeRateService.delete(base, quote);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/transfers")
    @Operation(summary = "All transfers (audit)")
    public ResponseEntity<List<TransferDto>> allTransfers() {
        return ResponseEntity.ok(transferService.listAll());
    }

    @GetMapping("/payments")
    @Operation(summary = "All payments (audit)")
    public ResponseEntity<List<PaymentDto>> allPayments() {
        return ResponseEntity.ok(paymentSagaService.listAll());
    }
}
