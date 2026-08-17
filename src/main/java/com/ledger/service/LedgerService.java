package com.ledger.service;

import com.ledger.exception.ConflictException;
import com.ledger.model.LedgerEntry;
import com.ledger.model.Wallet;
import com.ledger.repository.LedgerEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Event-sourced ledger with a materialized balance.
 * <p>The authoritative invariant is {@code balance = sum(entries)}. Every entry
 * is posted together with the wallet balance update inside one ACID
 * transaction, and posting is idempotent by {@code operation_key} (unique per
 * wallet), so a retried posting can never double-credit or double-debit.
 */
@Service
public class LedgerService {

    private final LedgerEntryRepository ledgerEntryRepository;

    public LedgerService(LedgerEntryRepository ledgerEntryRepository) {
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    @Transactional(readOnly = true)
    public BigDecimal currentBalance(Wallet wallet) {
        return ledgerEntryRepository.findFirstByWalletIdOrderByIdDesc(wallet.getId())
                .map(LedgerEntry::getBalanceAfter)
                .orElse(BigDecimal.ZERO);
    }

    /**
     * Posts an entry and updates the wallet balance. Callers must hold the
     * wallet lock (or otherwise serialize access) — the caller's transaction
     * covers both writes.
     */
    @Transactional
    public BigDecimal post(Wallet wallet, LedgerEntry.EntryType type, BigDecimal amount, String operationKey,
                           String description) {
        if (ledgerEntryRepository.existsByWalletIdAndOperationKey(wallet.getId(), operationKey)) {
            throw new ConflictException("Ledger entry already exists for wallet " + wallet.getId()
                    + " and operation " + operationKey);
        }
        BigDecimal current = wallet.getBalance();
        BigDecimal balanceAfter = (type == LedgerEntry.EntryType.CREDIT)
                ? current.add(amount)
                : current.subtract(amount);

        LedgerEntry entry = new LedgerEntry();
        entry.setWallet(wallet);
        entry.setEntryType(type);
        entry.setAmount(amount);
        entry.setBalanceAfter(balanceAfter);
        entry.setCurrency(wallet.getCurrency());
        entry.setDescription(description);
        entry.setOperationKey(operationKey);
        ledgerEntryRepository.save(entry);

        wallet.setBalance(balanceAfter);
        return balanceAfter;
    }
}
