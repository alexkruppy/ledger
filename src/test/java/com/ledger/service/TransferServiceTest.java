package com.ledger.service;

import com.ledger.exception.InsufficientFundsException;
import com.ledger.exception.BadRequestException;
import com.ledger.exception.ResourceNotFoundException;
import com.ledger.model.LedgerEntry;
import com.ledger.model.Transfer;
import com.ledger.model.User;
import com.ledger.model.Wallet;
import com.ledger.repository.TransferRepository;
import com.ledger.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.ledger.config.LedgerProperties;
import com.ledger.support.EntityIdSetter;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    @Mock private TransferRepository transferRepository;
    @Mock private WalletRepository walletRepository;
    @Mock private LedgerService ledgerService;
    @Mock private FxService fxService;
    @Mock private OutboxService outboxService;

    private TransferService transferService;

    private User user;
    private Wallet fromWallet;
    private Wallet toWallet;
    private Wallet feeWallet;

    @BeforeEach
    void setUp() {
        LedgerProperties props = new LedgerProperties(
                null, null,
                new LedgerProperties.Transfer(new BigDecimal("1.0"), null, null),
                null, null, null, null,
                new LedgerProperties.App("fees@ledger.internal", null, null));
        transferService = new TransferService(transferRepository, walletRepository,
                ledgerService, fxService, outboxService, props);

        user = new User();
        EntityIdSetter.setId(user, 10L);

        fromWallet = new Wallet();
        EntityIdSetter.setId(fromWallet, 1L);
        fromWallet.setCurrency("EUR");
        fromWallet.setBalance(new BigDecimal("1000.00000000"));
        fromWallet.setStatus(Wallet.Status.ACTIVE);
        fromWallet.setUser(user);

        toWallet = new Wallet();
        EntityIdSetter.setId(toWallet, 2L);
        toWallet.setCurrency("EUR");
        toWallet.setBalance(new BigDecimal("0.00000000"));
        toWallet.setStatus(Wallet.Status.ACTIVE);
        toWallet.setUser(new User());

        feeWallet = new Wallet();
        EntityIdSetter.setId(feeWallet, 99L);
        feeWallet.setCurrency("EUR");
        feeWallet.setUser(user);
    }

    @Test
    void sameWalletTransferThrows() {
        var req = new com.ledger.dto.TransferRequest(1L, 1L, new BigDecimal("10.00"));
        assertThatThrownBy(() -> transferService.transfer(user, req))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void fromWalletNotFoundThrows() {
        when(walletRepository.findByIdForUpdate(1L)).thenReturn(Optional.empty());
        var req = new com.ledger.dto.TransferRequest(1L, 2L, new BigDecimal("10.00"));
        assertThatThrownBy(() -> transferService.transfer(user, req))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void walletBelongsToAnotherUserThrows() {
        when(walletRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(fromWallet));
        User otherUser = new User();
        EntityIdSetter.setId(otherUser, 99L);
        fromWallet.setUser(otherUser);
        var req = new com.ledger.dto.TransferRequest(1L, 2L, new BigDecimal("10.00"));
        assertThatThrownBy(() -> transferService.transfer(user, req))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void frozenWalletThrows() {
        fromWallet.setStatus(Wallet.Status.FROZEN);
        when(walletRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(fromWallet));
        var req = new com.ledger.dto.TransferRequest(1L, 2L, new BigDecimal("10.00"));
        assertThatThrownBy(() -> transferService.transfer(user, req))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void insufficientFundsThrows() {
        fromWallet.setBalance(new BigDecimal("5.00000000"));
        when(walletRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(fromWallet));
        when(walletRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(toWallet));
        var req = new com.ledger.dto.TransferRequest(1L, 2L, new BigDecimal("100.00"));
        assertThatThrownBy(() -> transferService.transfer(user, req))
                .isInstanceOf(InsufficientFundsException.class);
    }

    @Test
    void successfulSameCurrencyTransfer() {
        when(walletRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(fromWallet));
        when(walletRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(toWallet));
        when(walletRepository.findSystemWallet("fees@ledger.internal", "EUR")).thenReturn(Optional.of(feeWallet));
        when(fxService.rate("EUR", "EUR")).thenReturn(BigDecimal.ONE);
        when(fxService.convert(any(BigDecimal.class), eq("EUR"), eq("EUR"))).thenAnswer(inv -> inv.getArgument(0));
        when(transferRepository.save(any())).thenAnswer(inv -> {
            Transfer t = inv.getArgument(0);
            EntityIdSetter.setId(t, 42L);
            return t;
        });
        when(ledgerService.post(any(), any(), any(), any(), any())).thenReturn(BigDecimal.ZERO);
        doNothing().when(outboxService).emit(any(), any(), any(), any());

        var req = new com.ledger.dto.TransferRequest(1L, 2L, new BigDecimal("100.00"));
        var result = transferService.transfer(user, req);

        assertThat(result).isNotNull();
        verify(transferRepository, atLeastOnce()).save(any());

        ArgumentCaptor<LedgerEntry.EntryType> typeCaptor = ArgumentCaptor.forClass(LedgerEntry.EntryType.class);
        verify(ledgerService, times(2)).post(any(), typeCaptor.capture(), any(), any(), any());
        assertThat(typeCaptor.getAllValues()).containsExactly(LedgerEntry.EntryType.DEBIT, LedgerEntry.EntryType.CREDIT);

        verify(outboxService).emit(eq("transfer"), anyString(), eq("TRANSFER_COMPLETED"), any());
    }
}
