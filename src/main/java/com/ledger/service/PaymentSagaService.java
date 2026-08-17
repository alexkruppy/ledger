package com.ledger.service;

import com.ledger.dto.PaymentDto;
import com.ledger.dto.TopUpRequest;
import com.ledger.dto.WithdrawRequest;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Saga orchestrator (Choreography via explicit steps) for external acquiring.
 * <p>Withdrawal reserves funds at initiation (debit), then:
 * <ul>
 *   <li>gateway success → payment COMPLETED (reservation stands)</li>
 *   <li>gateway failure → compensating credit returns the reserved funds, payment ROLLED_BACK</li>
 * </ul>
 * Top-up credits only after the gateway authorizes the charge. Every step is a
 * separate short transaction; the ledger {@code operation_key} prevents any
 * double-posting on retries.
 */
@Service
public class PaymentSagaService {

    private static final Logger log = LoggerFactory.getLogger(PaymentSagaService.class);

    private final PaymentTransactionRepository paymentRepository;
    private final WalletRepository walletRepository;
    private final LedgerService ledgerService;
    private final MockGatewayClient gatewayClient;
    private final OutboxService outboxService;

    public PaymentSagaService(PaymentTransactionRepository paymentRepository,
                              WalletRepository walletRepository,
                              LedgerService ledgerService,
                              MockGatewayClient gatewayClient,
                              OutboxService outboxService) {
        this.paymentRepository = paymentRepository;
        this.walletRepository = walletRepository;
        this.ledgerService = ledgerService;
        this.gatewayClient = gatewayClient;
        this.outboxService = outboxService;
    }

    public PaymentDto topUp(User user, Long walletId, TopUpRequest request, String idempotencyKey) {
        return runTopUp(user, walletId, request.amount(), idempotencyKey);
    }

    public PaymentDto withdraw(User user, Long walletId, WithdrawRequest request, String idempotencyKey) {
        return runWithdraw(user, walletId, request.amount(), idempotencyKey);
    }

    private PaymentDto runTopUp(User user, Long walletId, BigDecimal amount, String idempotencyKey) {
        PaymentTransaction payment = initiate(user, walletId, amount, PaymentTransaction.Type.TOP_UP, idempotencyKey);
        try {
            GatewayResponse gateway = gatewayClient.charge(payment.getCurrency(),
                    "topup-" + payment.getId(), amount);
            return settle(payment.getId(), gateway);
        } catch (Exception e) {
            return compensate(payment.getId(), e);
        }
    }

    private PaymentDto runWithdraw(User user, Long walletId, BigDecimal amount, String idempotencyKey) {
        PaymentTransaction payment = initiate(user, walletId, amount, PaymentTransaction.Type.WITHDRAW, idempotencyKey);
        try {
            GatewayResponse gateway = gatewayClient.payout(payment.getCurrency(),
                    "withdraw-" + payment.getId(), amount);
            return settle(payment.getId(), gateway);
        } catch (Exception e) {
            return compensate(payment.getId(), e);
        }
    }

    /** Step 1: create PENDING payment; for withdrawals reserve the funds by debiting. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PaymentTransaction initiate(User user, Long walletId, BigDecimal amount,
                                       PaymentTransaction.Type type, String idempotencyKey) {
        Wallet wallet = walletRepository.findByIdForUpdate(walletId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found with id: " + walletId));
        if (!wallet.getUser().getId().equals(user.getId())) {
            throw new ResourceNotFoundException("Wallet not found with id: " + walletId);
        }
        if (wallet.getStatus() != Wallet.Status.ACTIVE) {
            throw new BadRequestException("Wallet " + walletId + " is not active");
        }

        PaymentTransaction payment = new PaymentTransaction();
        payment.setIdempotencyKey(idempotencyKey);
        payment.setUser(user);
        payment.setWallet(wallet);
        payment.setType(type);
        payment.setAmount(amount);
        payment.setCurrency(wallet.getCurrency());
        payment.setStatus(PaymentTransaction.Status.PENDING);
        paymentRepository.save(payment);

        if (type == PaymentTransaction.Type.WITHDRAW) {
            if (wallet.getBalance().compareTo(amount) < 0) {
                throw new InsufficientFundsException(
                        "Insufficient funds: required " + amount + " " + wallet.getCurrency()
                                + ", available " + wallet.getBalance() + " " + wallet.getCurrency());
            }
            ledgerService.post(wallet, LedgerEntry.EntryType.DEBIT, amount,
                    "payment:" + payment.getId(),
                    "Withdrawal reservation to external bank account");
        }
        return payment;
    }

    /** Step 2 (forward): gateway succeeded — credit top-up, mark COMPLETED. */
    @Transactional
    public PaymentDto settle(Long paymentId, GatewayResponse gateway) {
        PaymentTransaction payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found with id: " + paymentId));
        if (payment.getStatus() != PaymentTransaction.Status.PENDING) {
            return PaymentDto.from(payment);
        }
        payment.setAttempts(payment.getAttempts() + 1);
        payment.setExternalPaymentId(gateway.id());

        if (payment.getType() == PaymentTransaction.Type.TOP_UP) {
            ledgerService.post(payment.getWallet(), LedgerEntry.EntryType.CREDIT, payment.getAmount(),
                    "payment:" + payment.getId(), "Top-up via external gateway " + gateway.id());
        }
        payment.setStatus(PaymentTransaction.Status.COMPLETED);
        paymentRepository.save(payment);

        outboxService.emit("payment", String.valueOf(payment.getId()), "PAYMENT_COMPLETED", eventPayload(payment));
        log.info("Payment {} settled: {} {} {}", payment.getId(), payment.getAmount(), payment.getCurrency(),
                payment.getStatus());
        return PaymentDto.from(payment);
    }

    /** Step 2 (compensation): gateway failed — refund reserved funds, mark ROLLED_BACK. */
    @Transactional
    public PaymentDto compensate(Long paymentId, Throwable failure) {
        PaymentTransaction payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found with id: " + paymentId));
        if (payment.getStatus() != PaymentTransaction.Status.PENDING) {
            return PaymentDto.from(payment);
        }
        payment.setAttempts(payment.getAttempts() + 1);
        payment.setFailureReason(failure.getMessage());

        if (payment.getType() == PaymentTransaction.Type.WITHDRAW) {
            ledgerService.post(payment.getWallet(), LedgerEntry.EntryType.CREDIT, payment.getAmount(),
                    "payment:" + payment.getId() + ":refund",
                    "Compensating refund of failed withdrawal");
        }
        payment.setStatus(PaymentTransaction.Status.ROLLED_BACK);
        paymentRepository.save(payment);

        outboxService.emit("payment", String.valueOf(payment.getId()), "PAYMENT_FAILED", eventPayload(payment));
        log.warn("Payment {} rolled back: {}", payment.getId(), failure.getMessage());
        return PaymentDto.from(payment);
    }

    @Transactional(readOnly = true)
    public PaymentDto get(Long userId, Long paymentId) {
        return paymentRepository.findByIdAndUserId(paymentId, userId)
                .map(PaymentDto::from)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found with id: " + paymentId));
    }

    @Transactional(readOnly = true)
    public java.util.List<PaymentDto> list(Long userId) {
        return paymentRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(PaymentDto::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public java.util.List<PaymentDto> listAll() {
        return paymentRepository.findAll(org.springframework.data.domain.PageRequest.of(0, 500))
                .map(PaymentDto::from).stream().toList();
    }

    private Map<String, Object> eventPayload(PaymentTransaction payment) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("paymentId", payment.getId());
        payload.put("userId", payment.getUser().getId());
        payload.put("walletId", payment.getWallet().getId());
        payload.put("type", payment.getType().name());
        payload.put("amount", payment.getAmount());
        payload.put("currency", payment.getCurrency());
        payload.put("status", payment.getStatus().name());
        payload.put("externalPaymentId", payment.getExternalPaymentId());
        return payload;
    }
}
