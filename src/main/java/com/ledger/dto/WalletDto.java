package com.ledger.dto;

import com.ledger.model.Wallet;

import java.math.BigDecimal;
import java.time.Instant;

public record WalletDto(Long id, String currency, String status, BigDecimal balance, Instant createdAt) {

    public static WalletDto of(Wallet w, BigDecimal balance) {
        return new WalletDto(w.getId(), w.getCurrency(), w.getStatus().name(), balance, w.getCreatedAt());
    }
}
