package com.ledger.dto;

import com.ledger.model.LedgerEntry;

import java.math.BigDecimal;
import java.time.Instant;

public record LedgerEntryDto(
        Long id,
        Long walletId,
        Long transferId,
        Long paymentId,
        String entryType,
        BigDecimal amount,
        BigDecimal balanceAfter,
        String currency,
        String description,
        String operationKey,
        Instant createdAt) {

    public static LedgerEntryDto from(LedgerEntry e) {
        return new LedgerEntryDto(
                e.getId(),
                e.getWallet().getId(),
                e.getTransfer() == null ? null : e.getTransfer().getId(),
                e.getPayment() == null ? null : e.getPayment().getId(),
                e.getEntryType().name(),
                e.getAmount(),
                e.getBalanceAfter(),
                e.getCurrency(),
                e.getDescription(),
                e.getOperationKey(),
                e.getCreatedAt());
    }
}
