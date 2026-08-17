package com.ledger.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

public record ExchangeRateUpdateRequest(
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String baseCurrency,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String quoteCurrency,
        @NotNull @DecimalMin(value = "0.000000000001", message = "Rate must be positive") BigDecimal rate) {
}
