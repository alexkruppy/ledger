package com.ledger.service;

import com.ledger.exception.ConflictException;
import com.ledger.model.LedgerEntry;
import com.ledger.model.Wallet;
import com.ledger.repository.LedgerEntryRepository;
import com.ledger.support.EntityIdSetter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    private LedgerService ledgerService;

    private Wallet wallet;

    @BeforeEach
    void setUp() {
        ledgerService = new LedgerService(ledgerEntryRepository);
        wallet = new Wallet();
        EntityIdSetter.setId(wallet, 1L);
        wallet.setCurrency("EUR");
        wallet.setBalance(new BigDecimal("100.00000000"));
    }

    @Test
    void currentBalanceReturnsZeroWhenNoEntries() {
        when(ledgerEntryRepository.findFirstByWalletIdOrderByIdDesc(1L)).thenReturn(Optional.empty());
        assertThat(ledgerService.currentBalance(wallet)).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void currentBalanceReturnsBalanceAfterFromLatestEntry() {
        LedgerEntry entry = new LedgerEntry();
        entry.setBalanceAfter(new BigDecimal("250.75000000"));
        when(ledgerEntryRepository.findFirstByWalletIdOrderByIdDesc(1L)).thenReturn(Optional.of(entry));
        assertThat(ledgerService.currentBalance(wallet)).isEqualByComparingTo("250.75000000");
    }

    @Test
    void postCreditIncreasesBalance() {
        when(ledgerEntryRepository.existsByWalletIdAndOperationKey(1L, "op:1")).thenReturn(false);
        when(ledgerEntryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BigDecimal result = ledgerService.post(wallet, LedgerEntry.EntryType.CREDIT,
                new BigDecimal("50.00000000"), "op:1", "Deposit");

        assertThat(result).isEqualByComparingTo("150.00000000");
        assertThat(wallet.getBalance()).isEqualByComparingTo("150.00000000");

        ArgumentCaptor<LedgerEntry> captor = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledgerEntryRepository).save(captor.capture());
        LedgerEntry saved = captor.getValue();
        assertThat(saved.getEntryType()).isEqualTo(LedgerEntry.EntryType.CREDIT);
        assertThat(saved.getAmount()).isEqualByComparingTo("50.00000000");
        assertThat(saved.getBalanceAfter()).isEqualByComparingTo("150.00000000");
        assertThat(saved.getWallet()).isSameAs(wallet);
    }

    @Test
    void postDebitDecreasesBalance() {
        when(ledgerEntryRepository.existsByWalletIdAndOperationKey(1L, "op:2")).thenReturn(false);
        when(ledgerEntryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BigDecimal result = ledgerService.post(wallet, LedgerEntry.EntryType.DEBIT,
                new BigDecimal("30.00000000"), "op:2", "Transfer out");

        assertThat(result).isEqualByComparingTo("70.00000000");
        assertThat(wallet.getBalance()).isEqualByComparingTo("70.00000000");
    }

    @Test
    void postDuplicateOperationKeyThrowsConflict() {
        when(ledgerEntryRepository.existsByWalletIdAndOperationKey(1L, "op:dup")).thenReturn(true);

        assertThatThrownBy(() -> ledgerService.post(wallet, LedgerEntry.EntryType.CREDIT,
                new BigDecimal("10.00000000"), "op:dup", "Duplicate"))
                .isInstanceOf(ConflictException.class);

        verify(ledgerEntryRepository, never()).save(any());
    }

    @Test
    void postOnZeroBalanceWallet() {
        wallet.setBalance(BigDecimal.ZERO);
        when(ledgerEntryRepository.existsByWalletIdAndOperationKey(1L, "op:3")).thenReturn(false);
        when(ledgerEntryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BigDecimal result = ledgerService.post(wallet, LedgerEntry.EntryType.DEBIT,
                new BigDecimal("0.00000000"), "op:3", "Zero debit");

        assertThat(result).isEqualByComparingTo("0.00000000");
    }
}
