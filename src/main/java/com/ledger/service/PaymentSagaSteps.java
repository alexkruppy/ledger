package com.ledger.service;

import com.ledger.dto.PaymentDto;
import com.ledger.exception.ResourceNotFoundException;
import com.ledger.gateway.GatewayResponse;
import com.ledger.model.LedgerEntry;
import com.ledger.model.PaymentTransaction;
import com.ledger.model.Wallet;
import com.ledger.repository.PaymentTransactionRepository;
import com.ledger.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

/**
 * Individual transactional steps of the payment saga, extracted into a
 * separate bean to avoid Spring proxy self-invocation issues.
 * Each method runs in its own transaction.
 */
@Service
public class PaymentSagaSteps {

    private static final Logger log = LoggerFactory.getLogger(PaymentSagaSteps.class);

    private final PaymentTransactionRepository paymentRepository;
    private final WalletRepository walletRepository;
    private final LedgerService ledgerService;
    private final OutboxService outboxService;

    public PaymentSagaSteps(PaymentTransactionRepository paymentRepository,
                            WalletRepository walletRepository,
                            LedgerService ledgerService,
                            OutboxService outboxService) {
        this.paymentRepository = paymentRepository;
        this.walletRepository = walletRepository;
        this.ledgerService = ledgerService;
        this.outboxService = outboxService;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PaymentTransaction initiate(Wallet wallet, PaymentTransaction payment) {
        Wallet locked = walletRepository.findByIdForUpdate(wallet.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found with id: " + wallet.getId()));
        if (locked.getStatus() != Wallet.Status.ACTIVE) {
            throw new com.ledger.exception.BadRequestException("Wallet " + wallet.getId() + " is not active");
        }
        payment.setWallet(locked);
        paymentRepository.save(payment);

        if (payment.getType() == PaymentTransaction.Type.WITHDRAW) {
            if (locked.getBalance().compareTo(payment.getAmount()) < 0) {
                throw new com.ledger.exception.InsufficientFundsException(
                        "Insufficient funds: required " + payment.getAmount() + " " + locked.getCurrency()
                                + ", available " + locked.getBalance() + " " + locked.getCurrency());
            }
            ledgerService.post(locked, LedgerEntry.EntryType.DEBIT, payment.getAmount(),
                    "payment:" + payment.getId(),
                    "Withdrawal reservation to external bank account");
        }
        return payment;
    }

    @Transactional
    public PaymentTransaction settle(Long paymentId, GatewayResponse gateway) {
        PaymentTransaction payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found with id: " + paymentId));
        if (payment.getStatus() != PaymentTransaction.Status.PENDING) {
            return payment;
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
        return payment;
    }

    @Transactional
    public PaymentTransaction compensate(Long paymentId, Throwable failure) {
        PaymentTransaction payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found with id: " + paymentId));
        if (payment.getStatus() != PaymentTransaction.Status.PENDING) {
            return payment;
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
        return payment;
    }

    @Transactional(readOnly = true)
    public PaymentDto getPayment(Long userId, Long paymentId) {
        return paymentRepository.findByIdAndUserId(paymentId, userId)
                .map(PaymentDto::from)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found with id: " + paymentId));
    }

    @Transactional(readOnly = true)
    public java.util.List<PaymentDto> listPayments(Long userId) {
        return paymentRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(PaymentDto::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public java.util.List<PaymentDto> listAllPayments() {
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
