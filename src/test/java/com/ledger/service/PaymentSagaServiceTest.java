package com.ledger.service;

import com.ledger.dto.PaymentDto;
import com.ledger.exception.BadRequestException;
import com.ledger.exception.InsufficientFundsException;
import com.ledger.exception.ResourceNotFoundException;
import com.ledger.gateway.GatewayResponse;
import com.ledger.gateway.MockGatewayClient;
import com.ledger.model.LedgerEntry;
import com.ledger.model.PaymentTransaction;
import com.ledger.model.User;
import com.ledger.model.Wallet;
import com.ledger.repository.PaymentTransactionRepository;
import com.ledger.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.ledger.support.EntityIdSetter;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentSagaServiceTest {

    @Mock private PaymentTransactionRepository paymentRepository;
    @Mock private WalletRepository walletRepository;
    @Mock private LedgerService ledgerService;
    @Mock private MockGatewayClient gatewayClient;
    @Mock private OutboxService outboxService;

    private PaymentSagaService sagaService;

    private User user;
    private Wallet wallet;

    @BeforeEach
    void setUp() {
        sagaService = new PaymentSagaService(paymentRepository, walletRepository,
                ledgerService, gatewayClient, outboxService);

        user = new User();
        EntityIdSetter.setId(user, 10L);

        wallet = new Wallet();
        EntityIdSetter.setId(wallet, 1L);
        wallet.setCurrency("EUR");
        wallet.setBalance(new BigDecimal("500.00000000"));
        wallet.setStatus(Wallet.Status.ACTIVE);
        wallet.setUser(user);
    }

    @Test
    void topUpSuccess() {
        when(walletRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(wallet));
        when(paymentRepository.save(any())).thenAnswer(inv -> {
            PaymentTransaction p = inv.getArgument(0);
            EntityIdSetter.setId(p, 100L);
            return p;
        });
        when(gatewayClient.charge("EUR", "topup-100", new BigDecimal("200.00")))
                .thenReturn(new GatewayResponse("gw-123", "approved"));
        when(paymentRepository.findByIdForUpdate(100L)).thenAnswer(inv -> {
            PaymentTransaction p = new PaymentTransaction();
            EntityIdSetter.setId(p, 100L);
            p.setStatus(PaymentTransaction.Status.PENDING);
            p.setType(PaymentTransaction.Type.TOP_UP);
            p.setAmount(new BigDecimal("200.00"));
            p.setCurrency("EUR");
            p.setUser(user);
            p.setWallet(wallet);
            return Optional.of(p);
        });
        when(ledgerService.post(any(), any(), any(), any(), any())).thenReturn(BigDecimal.ZERO);
        doNothing().when(outboxService).emit(any(), any(), any(), any());

        PaymentDto result = sagaService.topUp(user, 1L,
                new com.ledger.dto.TopUpRequest(new BigDecimal("200.00"), null), "idem-topup");

        assertThat(result).isNotNull();
        verify(ledgerService).post(eq(wallet), eq(LedgerEntry.EntryType.CREDIT),
                eq(new BigDecimal("200.00")), anyString(), anyString());
    }

    @Test
    void topUpGatewayFailureCompensates() {
        when(walletRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(wallet));
        when(paymentRepository.save(any())).thenAnswer(inv -> {
            PaymentTransaction p = inv.getArgument(0);
            EntityIdSetter.setId(p, 101L);
            return p;
        });
        when(gatewayClient.charge(anyString(), anyString(), any(BigDecimal.class)))
                .thenThrow(new com.ledger.gateway.GatewayClientException("declined"));
        when(paymentRepository.findByIdForUpdate(101L)).thenAnswer(inv -> {
            PaymentTransaction p = new PaymentTransaction();
            EntityIdSetter.setId(p, 101L);
            p.setStatus(PaymentTransaction.Status.PENDING);
            p.setType(PaymentTransaction.Type.TOP_UP);
            p.setAmount(new BigDecimal("100.00"));
            p.setCurrency("EUR");
            p.setUser(user);
            p.setWallet(wallet);
            return Optional.of(p);
        });
        doNothing().when(outboxService).emit(any(), any(), any(), any());

        PaymentDto result = sagaService.topUp(user, 1L,
                new com.ledger.dto.TopUpRequest(new BigDecimal("100.00"), null), "idem-fail");

        assertThat(result).isNotNull();
        verify(ledgerService, never()).post(any(), any(), any(), any(), any());
    }

    @Test
    void withdrawSuccessReservesAndSettles() {
        when(walletRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(wallet));
        when(paymentRepository.save(any())).thenAnswer(inv -> {
            PaymentTransaction p = inv.getArgument(0);
            EntityIdSetter.setId(p, 200L);
            return p;
        });
        when(ledgerService.post(any(), any(), any(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(gatewayClient.payout("EUR", "withdraw-200", new BigDecimal("150.00")))
                .thenReturn(new GatewayResponse("gw-456", "approved"));
        when(paymentRepository.findByIdForUpdate(200L)).thenAnswer(inv -> {
            PaymentTransaction p = new PaymentTransaction();
            EntityIdSetter.setId(p, 200L);
            p.setStatus(PaymentTransaction.Status.PENDING);
            p.setType(PaymentTransaction.Type.WITHDRAW);
            p.setAmount(new BigDecimal("150.00"));
            p.setCurrency("EUR");
            p.setUser(user);
            p.setWallet(wallet);
            return Optional.of(p);
        });
        doNothing().when(outboxService).emit(any(), any(), any(), any());

        PaymentDto result = sagaService.withdraw(user, 1L,
                new com.ledger.dto.WithdrawRequest(new BigDecimal("150.00"), null), "idem-w1");

        assertThat(result).isNotNull();
        ArgumentCaptor<LedgerEntry.EntryType> types = ArgumentCaptor.forClass(LedgerEntry.EntryType.class);
        verify(ledgerService, atLeastOnce()).post(eq(wallet), types.capture(), any(), any(), any());
        assertThat(types.getAllValues().get(0)).isEqualTo(LedgerEntry.EntryType.DEBIT);
    }

    @Test
    void withdrawInsufficientFundsThrows() {
        wallet.setBalance(new BigDecimal("10.00000000"));
        when(walletRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(wallet));
        when(paymentRepository.save(any())).thenAnswer(inv -> {
            PaymentTransaction p = inv.getArgument(0);
            EntityIdSetter.setId(p, 201L);
            return p;
        });

        assertThatThrownBy(() -> sagaService.withdraw(user, 1L,
                new com.ledger.dto.WithdrawRequest(new BigDecimal("100.00"), null), "idem-w2"))
                .isInstanceOf(InsufficientFundsException.class);
    }

    @Test
    void walletNotFoundThrows() {
        when(walletRepository.findByIdForUpdate(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sagaService.topUp(user, 999L,
                new com.ledger.dto.TopUpRequest(new BigDecimal("50.00"), null), "idem-nf"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void walletBelongsToAnotherUserThrows() {
        User other = new User();
        EntityIdSetter.setId(other, 99L);
        wallet.setUser(other);
        when(walletRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(wallet));

        assertThatThrownBy(() -> sagaService.topUp(user, 1L,
                new com.ledger.dto.TopUpRequest(new BigDecimal("50.00"), null), "idem-ow"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void frozenWalletThrows() {
        wallet.setStatus(Wallet.Status.FROZEN);
        when(walletRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(wallet));

        assertThatThrownBy(() -> sagaService.topUp(user, 1L,
                new com.ledger.dto.TopUpRequest(new BigDecimal("50.00"), null), "idem-fz"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void withdrawGatewayFailureCompensates() {
        when(walletRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(wallet));
        when(paymentRepository.save(any())).thenAnswer(inv -> {
            PaymentTransaction p = inv.getArgument(0);
            EntityIdSetter.setId(p, 300L);
            return p;
        });
        when(ledgerService.post(any(), any(), any(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(gatewayClient.payout(anyString(), anyString(), any(BigDecimal.class)))
                .thenThrow(new com.ledger.gateway.GatewayClientException("bank declined"));
        when(paymentRepository.findByIdForUpdate(300L)).thenAnswer(inv -> {
            PaymentTransaction p = new PaymentTransaction();
            EntityIdSetter.setId(p, 300L);
            p.setStatus(PaymentTransaction.Status.PENDING);
            p.setType(PaymentTransaction.Type.WITHDRAW);
            p.setAmount(new BigDecimal("80.00"));
            p.setCurrency("EUR");
            p.setUser(user);
            p.setWallet(wallet);
            return Optional.of(p);
        });
        doNothing().when(outboxService).emit(any(), any(), any(), any());

        PaymentDto result = sagaService.withdraw(user, 1L,
                new com.ledger.dto.WithdrawRequest(new BigDecimal("80.00"), null), "idem-wf");

        assertThat(result).isNotNull();
        ArgumentCaptor<LedgerEntry.EntryType> types = ArgumentCaptor.forClass(LedgerEntry.EntryType.class);
        verify(ledgerService, times(2)).post(eq(wallet), types.capture(), any(), any(), any());
        assertThat(types.getAllValues()).containsExactly(LedgerEntry.EntryType.DEBIT, LedgerEntry.EntryType.CREDIT);
    }
}
