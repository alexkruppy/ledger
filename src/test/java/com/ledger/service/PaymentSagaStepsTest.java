package com.ledger.service;

import com.ledger.exception.BadRequestException;
import com.ledger.exception.InsufficientFundsException;
import com.ledger.exception.ResourceNotFoundException;
import com.ledger.gateway.GatewayResponse;
import com.ledger.model.LedgerEntry;
import com.ledger.model.PaymentTransaction;
import com.ledger.model.User;
import com.ledger.model.Wallet;
import com.ledger.repository.PaymentTransactionRepository;
import com.ledger.repository.WalletRepository;
import com.ledger.support.EntityIdSetter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentSagaStepsTest {

    @Mock private PaymentTransactionRepository paymentRepository;
    @Mock private WalletRepository walletRepository;
    @Mock private LedgerService ledgerService;
    @Mock private OutboxService outboxService;

    private PaymentSagaSteps steps;

    private User user;
    private Wallet wallet;

    @BeforeEach
    void setUp() {
        steps = new PaymentSagaSteps(paymentRepository, walletRepository, ledgerService, outboxService);

        user = new User();
        EntityIdSetter.setId(user, 10L);

        wallet = new Wallet();
        EntityIdSetter.setId(wallet, 1L);
        wallet.setCurrency("EUR");
        wallet.setBalance(new BigDecimal("500.00000000"));
        wallet.setStatus(Wallet.Status.ACTIVE);
        wallet.setUser(user);
    }

    private PaymentTransaction createPendingPayment(long id, PaymentTransaction.Type type, BigDecimal amount) {
        PaymentTransaction p = new PaymentTransaction();
        EntityIdSetter.setId(p, id);
        p.setStatus(PaymentTransaction.Status.PENDING);
        p.setType(type);
        p.setAmount(amount);
        p.setCurrency("EUR");
        p.setUser(user);
        p.setWallet(wallet);
        return p;
    }

    @Test
    void initiateTopUpSavesWithoutDebit() {
        when(walletRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(wallet));
        when(paymentRepository.save(any())).thenAnswer(inv -> {
            PaymentTransaction p = inv.getArgument(0);
            EntityIdSetter.setId(p, 100L);
            return p;
        });

        PaymentTransaction payment = createPendingPayment(0L, PaymentTransaction.Type.TOP_UP, new BigDecimal("200.00"));
        PaymentTransaction result = steps.initiate(wallet, payment);

        assertThat(result).isNotNull();
        verify(paymentRepository).save(any());
        verify(ledgerService, never()).post(any(), any(), any(), any(), any());
    }

    @Test
    void initiateWithdrawDebitsAndChecksBalance() {
        when(walletRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(wallet));
        when(paymentRepository.save(any())).thenAnswer(inv -> {
            PaymentTransaction p = inv.getArgument(0);
            EntityIdSetter.setId(p, 200L);
            return p;
        });
        when(ledgerService.post(any(), any(), any(), any(), any())).thenReturn(BigDecimal.ZERO);

        PaymentTransaction payment = createPendingPayment(0L, PaymentTransaction.Type.WITHDRAW, new BigDecimal("150.00"));
        PaymentTransaction result = steps.initiate(wallet, payment);

        assertThat(result).isNotNull();
        verify(ledgerService).post(eq(wallet), eq(LedgerEntry.EntryType.DEBIT), eq(new BigDecimal("150.00")), anyString(), anyString());
    }

    @Test
    void initiateWithdrawInsufficientFundsThrows() {
        wallet.setBalance(new BigDecimal("10.00000000"));
        when(walletRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(wallet));
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PaymentTransaction payment = createPendingPayment(0L, PaymentTransaction.Type.WITHDRAW, new BigDecimal("100.00"));
        assertThatThrownBy(() -> steps.initiate(wallet, payment))
                .isInstanceOf(InsufficientFundsException.class);
    }

    @Test
    void initiateFrozenWalletThrows() {
        wallet.setStatus(Wallet.Status.FROZEN);
        when(walletRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(wallet));

        PaymentTransaction payment = createPendingPayment(0L, PaymentTransaction.Type.TOP_UP, new BigDecimal("50.00"));
        assertThatThrownBy(() -> steps.initiate(wallet, payment))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void initiateWalletNotFoundThrows() {
        when(walletRepository.findByIdForUpdate(1L)).thenReturn(Optional.empty());

        PaymentTransaction payment = createPendingPayment(0L, PaymentTransaction.Type.TOP_UP, new BigDecimal("50.00"));
        assertThatThrownBy(() -> steps.initiate(wallet, payment))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void settleMarksCompletedAndCredits() {
        PaymentTransaction pending = createPendingPayment(100L, PaymentTransaction.Type.TOP_UP, new BigDecimal("200.00"));
        when(paymentRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(pending));
        when(ledgerService.post(any(), any(), any(), any(), any())).thenReturn(BigDecimal.ZERO);

        GatewayResponse gw = new GatewayResponse("gw-123", "approved");
        PaymentTransaction result = steps.settle(100L, gw);

        assertThat(result.getStatus()).isEqualTo(PaymentTransaction.Status.COMPLETED);
        verify(ledgerService).post(eq(wallet), eq(LedgerEntry.EntryType.CREDIT), eq(new BigDecimal("200.00")), anyString(), anyString());
        verify(outboxService).emit(eq("payment"), eq("100"), eq("PAYMENT_COMPLETED"), any());
    }

    @Test
    void compensateMarksRolledBack() {
        PaymentTransaction pending = createPendingPayment(101L, PaymentTransaction.Type.TOP_UP, new BigDecimal("100.00"));
        when(paymentRepository.findByIdForUpdate(101L)).thenReturn(Optional.of(pending));

        PaymentTransaction result = steps.compensate(101L, new RuntimeException("declined"));

        assertThat(result.getStatus()).isEqualTo(PaymentTransaction.Status.ROLLED_BACK);
        assertThat(result.getFailureReason()).isEqualTo("declined");
        verify(outboxService).emit(eq("payment"), eq("101"), eq("PAYMENT_FAILED"), any());
    }

    @Test
    void compensateWithdrawRefundsDebit() {
        PaymentTransaction pending = createPendingPayment(300L, PaymentTransaction.Type.WITHDRAW, new BigDecimal("80.00"));
        when(paymentRepository.findByIdForUpdate(300L)).thenReturn(Optional.of(pending));
        when(ledgerService.post(any(), any(), any(), any(), any())).thenReturn(BigDecimal.ZERO);

        PaymentTransaction result = steps.compensate(300L, new RuntimeException("bank declined"));

        assertThat(result.getStatus()).isEqualTo(PaymentTransaction.Status.ROLLED_BACK);
        verify(ledgerService).post(eq(wallet), eq(LedgerEntry.EntryType.CREDIT), eq(new BigDecimal("80.00")), anyString(), anyString());
    }
}
