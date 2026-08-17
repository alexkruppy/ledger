package com.ledger.service;

import com.ledger.dto.PaymentDto;
import com.ledger.exception.InsufficientFundsException;
import com.ledger.exception.ResourceNotFoundException;
import com.ledger.gateway.GatewayClientException;
import com.ledger.gateway.GatewayResponse;
import com.ledger.gateway.MockGatewayClient;
import com.ledger.model.PaymentTransaction;
import com.ledger.model.User;
import com.ledger.model.Wallet;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentSagaServiceTest {

    @Mock private WalletRepository walletRepository;
    @Mock private MockGatewayClient gatewayClient;
    @Mock private PaymentSagaSteps steps;

    private PaymentSagaService sagaService;

    private User user;
    private Wallet wallet;

    @BeforeEach
    void setUp() {
        sagaService = new PaymentSagaService(walletRepository, gatewayClient, steps);

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
    void topUpSuccess() {
        when(walletRepository.findById(1L)).thenReturn(Optional.of(wallet));
        PaymentTransaction initiated = createPendingPayment(100L, PaymentTransaction.Type.TOP_UP, new BigDecimal("200.00"));
        when(steps.initiate(eq(wallet), any())).thenReturn(initiated);
        PaymentTransaction settled = createPendingPayment(100L, PaymentTransaction.Type.TOP_UP, new BigDecimal("200.00"));
        settled.setStatus(PaymentTransaction.Status.COMPLETED);
        when(steps.settle(eq(100L), any(GatewayResponse.class))).thenReturn(settled);
        when(gatewayClient.charge("EUR", "topup-100", new BigDecimal("200.00")))
                .thenReturn(new GatewayResponse("gw-123", "approved"));

        PaymentDto result = sagaService.topUp(user, 1L,
                new com.ledger.dto.TopUpRequest(new BigDecimal("200.00"), null), "idem-topup");

        assertThat(result).isNotNull();
        verify(steps).initiate(eq(wallet), any());
        verify(steps).settle(eq(100L), any(GatewayResponse.class));
    }

    @Test
    void topUpGatewayFailureCompensates() {
        when(walletRepository.findById(1L)).thenReturn(Optional.of(wallet));
        PaymentTransaction initiated = createPendingPayment(101L, PaymentTransaction.Type.TOP_UP, new BigDecimal("100.00"));
        when(steps.initiate(eq(wallet), any())).thenReturn(initiated);
        when(gatewayClient.charge(anyString(), anyString(), any(BigDecimal.class)))
                .thenThrow(new GatewayClientException("declined"));
        PaymentTransaction compensated = createPendingPayment(101L, PaymentTransaction.Type.TOP_UP, new BigDecimal("100.00"));
        compensated.setStatus(PaymentTransaction.Status.ROLLED_BACK);
        when(steps.compensate(eq(101L), any())).thenReturn(compensated);

        PaymentDto result = sagaService.topUp(user, 1L,
                new com.ledger.dto.TopUpRequest(new BigDecimal("100.00"), null), "idem-fail");

        assertThat(result).isNotNull();
        verify(steps, never()).settle(anyLong(), any());
        verify(steps).compensate(eq(101L), any());
    }

    @Test
    void withdrawSuccessReservesAndSettles() {
        when(walletRepository.findById(1L)).thenReturn(Optional.of(wallet));
        PaymentTransaction initiated = createPendingPayment(200L, PaymentTransaction.Type.WITHDRAW, new BigDecimal("150.00"));
        when(steps.initiate(eq(wallet), any())).thenReturn(initiated);
        when(gatewayClient.payout("EUR", "withdraw-200", new BigDecimal("150.00")))
                .thenReturn(new GatewayResponse("gw-456", "approved"));
        PaymentTransaction settled = createPendingPayment(200L, PaymentTransaction.Type.WITHDRAW, new BigDecimal("150.00"));
        settled.setStatus(PaymentTransaction.Status.COMPLETED);
        when(steps.settle(eq(200L), any(GatewayResponse.class))).thenReturn(settled);

        PaymentDto result = sagaService.withdraw(user, 1L,
                new com.ledger.dto.WithdrawRequest(new BigDecimal("150.00"), null), "idem-w1");

        assertThat(result).isNotNull();
        verify(steps).initiate(eq(wallet), any());
        verify(steps).settle(eq(200L), any(GatewayResponse.class));
    }

    @Test
    void withdrawGatewayFailureCompensates() {
        when(walletRepository.findById(1L)).thenReturn(Optional.of(wallet));
        PaymentTransaction initiated = createPendingPayment(300L, PaymentTransaction.Type.WITHDRAW, new BigDecimal("80.00"));
        when(steps.initiate(eq(wallet), any())).thenReturn(initiated);
        when(gatewayClient.payout(anyString(), anyString(), any(BigDecimal.class)))
                .thenThrow(new GatewayClientException("bank declined"));
        PaymentTransaction compensated = createPendingPayment(300L, PaymentTransaction.Type.WITHDRAW, new BigDecimal("80.00"));
        compensated.setStatus(PaymentTransaction.Status.ROLLED_BACK);
        when(steps.compensate(eq(300L), any())).thenReturn(compensated);

        PaymentDto result = sagaService.withdraw(user, 1L,
                new com.ledger.dto.WithdrawRequest(new BigDecimal("80.00"), null), "idem-wf");

        assertThat(result).isNotNull();
        verify(steps).compensate(eq(300L), any());
    }

    @Test
    void walletNotFoundThrows() {
        when(walletRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sagaService.topUp(user, 999L,
                new com.ledger.dto.TopUpRequest(new BigDecimal("50.00"), null), "idem-nf"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void walletBelongsToAnotherUserThrows() {
        User other = new User();
        EntityIdSetter.setId(other, 99L);
        wallet.setUser(other);
        when(walletRepository.findById(1L)).thenReturn(Optional.of(wallet));

        assertThatThrownBy(() -> sagaService.topUp(user, 1L,
                new com.ledger.dto.TopUpRequest(new BigDecimal("50.00"), null), "idem-ow"))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
