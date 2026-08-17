package com.ledger.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateWalletRequest(
        @NotBlank
        @Pattern(regexp = "[A-Z]{3}", message = "Currency must be a 3-letter ISO code")
        @Size(min = 3, max = 3)
        String currency) {
}
