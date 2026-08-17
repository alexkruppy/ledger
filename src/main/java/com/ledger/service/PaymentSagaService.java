package com.ledger.service;

import com.ledger.dto.PaymentDto;
import com.ledger.dto.TopUpRequest;
import com.ledger.dto.WithdrawRequest;
import com.ledger.gateway.GatewayResponse;
import com.ledger.gateway.MockGatewayClient;
import com.ledger.model.PaymentTransaction;
import com.ledger.model.User;
import com.ledger.model.Wallet;
import com.ledger.repository.WalletRepository;
import com.ledger.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Saga orchestrator for external acquiring.
 * Transactional steps are delegated to {@link PaymentSagaSteps} to avoid
 * Spring proxy self-invocation issues (which bypass {@code @Transactional}).
 */
@Service
public class PaymentSagaService {

    private static final Logger log = LoggerFactory.getLogger(PaymentSagaService.class);

    private final WalletRepository walletRepository;
    private final MockGatewayClient gatewayClient;
    private final PaymentSagaSteps steps;

    public PaymentSagaService(WalletRepository walletRepository,
                              MockGatewayClient gatewayClient,
                              PaymentSagaSteps steps) {
        this.walletRepository = walletRepository;
        this.gatewayClient = gatewayClient;
        this.steps = steps;
    }

    public PaymentDto topUp(User user, Long walletId, TopUpRequest request, String idempotencyKey) {
        return runTopUp(user, walletId, request.amount(), idempotencyKey);
    }

    public PaymentDto withdraw(User user, Long walletId, WithdrawRequest request, String idempotencyKey) {
        return runWithdraw(user, walletId, request.amount(), idempotencyKey);
    }

    private PaymentDto runTopUp(User user, Long walletId, BigDecimal amount, String idempotencyKey) {
        Wallet wallet = walletRepository.findById(walletId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found with id: " + walletId));
        if (!wallet.getUser().getId().equals(user.getId())) {
            throw new ResourceNotFoundException("Wallet not found with id: " + walletId);
        }

        PaymentTransaction payment = new PaymentTransaction();
        payment.setIdempotencyKey(idempotencyKey);
        payment.setUser(user);
        payment.setWallet(wallet);
        payment.setType(PaymentTransaction.Type.TOP_UP);
        payment.setAmount(amount);
        payment.setCurrency(wallet.getCurrency());
        payment.setStatus(PaymentTransaction.Status.PENDING);

        PaymentTransaction initiated = steps.initiate(wallet, payment);
        try {
            GatewayResponse gateway = gatewayClient.charge(initiated.getCurrency(),
                    "topup-" + initiated.getId(), amount);
            return PaymentDto.from(steps.settle(initiated.getId(), gateway));
        } catch (Exception e) {
            return PaymentDto.from(steps.compensate(initiated.getId(), e));
        }
    }

    private PaymentDto runWithdraw(User user, Long walletId, BigDecimal amount, String idempotencyKey) {
        Wallet wallet = walletRepository.findById(walletId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found with id: " + walletId));
        if (!wallet.getUser().getId().equals(user.getId())) {
            throw new ResourceNotFoundException("Wallet not found with id: " + walletId);
        }

        PaymentTransaction payment = new PaymentTransaction();
        payment.setIdempotencyKey(idempotencyKey);
        payment.setUser(user);
        payment.setWallet(wallet);
        payment.setType(PaymentTransaction.Type.WITHDRAW);
        payment.setAmount(amount);
        payment.setCurrency(wallet.getCurrency());
        payment.setStatus(PaymentTransaction.Status.PENDING);

        PaymentTransaction initiated = steps.initiate(wallet, payment);
        try {
            GatewayResponse gateway = gatewayClient.payout(initiated.getCurrency(),
                    "withdraw-" + initiated.getId(), amount);
            return PaymentDto.from(steps.settle(initiated.getId(), gateway));
        } catch (Exception e) {
            return PaymentDto.from(steps.compensate(initiated.getId(), e));
        }
    }

    @Transactional(readOnly = true)
    public PaymentDto get(Long userId, Long paymentId) {
        return steps.getPayment(userId, paymentId);
    }

    @Transactional(readOnly = true)
    public java.util.List<PaymentDto> list(Long userId) {
        return steps.listPayments(userId);
    }

    @Transactional(readOnly = true)
    public java.util.List<PaymentDto> listAll() {
        return steps.listAllPayments();
    }
}
