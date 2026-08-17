package com.ledger.dto;

import com.ledger.model.Transfer;

import java.math.BigDecimal;
import java.time.Instant;

public record TransferDto(
        Long id,
        Long fromWalletId,
        Long toWalletId,
        String currency,
        BigDecimal amount,
        BigDecimal convertedAmount,
        BigDecimal fxRate,
        BigDecimal fee,
        String status,
        String errorMessage,
        Instant createdAt,
        Instant completedAt) {

    public static TransferDto from(Transfer t) {
        return new TransferDto(
                t.getId(),
                t.getFromWallet() == null ? null : t.getFromWallet().getId(),
                t.getToWallet() == null ? null : t.getToWallet().getId(),
                t.getCurrency(),
                t.getAmount(),
                t.getConvertedAmount(),
                t.getFxRate(),
                t.getFee(),
                t.getStatus().name(),
                t.getErrorMessage(),
                t.getCreatedAt(),
                t.getCompletedAt());
    }
}
