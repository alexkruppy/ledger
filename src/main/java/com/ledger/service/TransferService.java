package com.ledger.service;

import com.ledger.config.LedgerProperties;
import com.ledger.dto.TransferDto;
import com.ledger.dto.TransferRequest;
import com.ledger.exception.BadRequestException;
import com.ledger.exception.InsufficientFundsException;
import com.ledger.exception.ResourceNotFoundException;
import com.ledger.model.LedgerEntry;
import com.ledger.model.Transfer;
import com.ledger.model.User;
import com.ledger.model.Wallet;
import com.ledger.repository.TransferRepository;
import com.ledger.repository.WalletRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Internal P2P transfers (possibly cross-currency).
 * <p>Double-spend protection: both wallets are locked with
 * {@code SELECT ... FOR UPDATE} (pessimistic) before any balance is read or
 * written, so concurrent transfers from the same wallet serialize on the row
 * lock. The ledger {@code operation_key} is a second line of defence.
 */
@Service
public class TransferService {

    private static final int MONEY_SCALE = 8;

    private final TransferRepository transferRepository;
    private final WalletRepository walletRepository;
    private final LedgerService ledgerService;
    private final FxService fxService;
    private final OutboxService outboxService;
    private final LedgerProperties properties;

    public TransferService(TransferRepository transferRepository,
                           WalletRepository walletRepository,
                           LedgerService ledgerService,
                           FxService fxService,
                           OutboxService outboxService,
                           LedgerProperties properties) {
        this.transferRepository = transferRepository;
        this.walletRepository = walletRepository;
        this.ledgerService = ledgerService;
        this.fxService = fxService;
        this.outboxService = outboxService;
        this.properties = properties;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransferDto transfer(User user, TransferRequest request) {
        if (request.fromWalletId().equals(request.toWalletId())) {
            throw new BadRequestException("Source and destination wallets must differ");
        }

        Wallet from = walletRepository.findByIdForUpdate(request.fromWalletId())
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found with id: " + request.fromWalletId()));
        Wallet to = walletRepository.findByIdForUpdate(request.toWalletId())
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found with id: " + request.toWalletId()));

        if (!from.getUser().getId().equals(user.getId())) {
            throw new ResourceNotFoundException("Wallet not found with id: " + request.fromWalletId());
        }
        requireActive(from);
        requireActive(to);

        BigDecimal amount = request.amount();
        BigDecimal fee = computeFee(amount);
        BigDecimal totalDebit = amount.add(fee).setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        if (from.getBalance().compareTo(totalDebit) < 0) {
            throw new InsufficientFundsException(
                    "Insufficient funds: required " + totalDebit + " " + from.getCurrency()
                            + ", available " + from.getBalance() + " " + from.getCurrency());
        }

        // Cross-currency conversion: recipient gets `amount` converted into its currency.
        BigDecimal rate = fxService.rate(from.getCurrency(), to.getCurrency());
        BigDecimal converted = fxService.convert(amount, from.getCurrency(), to.getCurrency());

        Transfer transfer = new Transfer();
        transfer.setIdempotencyKey(java.util.UUID.randomUUID().toString());
        transfer.setUser(user);
        transfer.setFromWallet(from);
        transfer.setToWallet(to);
        transfer.setCurrency(from.getCurrency());
        transfer.setAmount(amount);
        transfer.setConvertedAmount(converted);
        transfer.setFxRate(rate);
        transfer.setFee(fee);
        transferRepository.save(transfer);

        String opKey = "transfer:" + transfer.getId();
        ledgerService.post(from, LedgerEntry.EntryType.DEBIT, totalDebit, opKey,
                "Transfer to wallet " + to.getId() + " (+fee " + fee + " " + from.getCurrency() + ")");
        ledgerService.post(to, LedgerEntry.EntryType.CREDIT, converted, opKey,
                "Transfer from wallet " + from.getId());

        if (fee.compareTo(BigDecimal.ZERO) > 0) {
            Wallet feeWallet = walletRepository.findSystemWallet(properties.app().feeWalletOwnerEmail(), from.getCurrency())
                    .orElse(null);
            if (feeWallet != null && !feeWallet.getId().equals(from.getId())) {
                walletRepository.findByIdForUpdate(feeWallet.getId()).ifPresent(locked -> {
                    ledgerService.post(locked, LedgerEntry.EntryType.CREDIT, fee, opKey,
                            "Transfer fee from wallet " + from.getId());
                });
            }
        }

        transfer.setStatus(Transfer.Status.COMPLETED);
        transfer.setCompletedAt(Instant.now());
        transferRepository.save(transfer);

        outboxService.emit("transfer", String.valueOf(transfer.getId()), "TRANSFER_COMPLETED", eventPayload(transfer));

        return TransferDto.from(transfer);
    }

    @Transactional(readOnly = true)
    public TransferDto get(Long userId, Long transferId) {
        return transferRepository.findByIdAndUserId(transferId, userId)
                .map(TransferDto::from)
                .orElseThrow(() -> new ResourceNotFoundException("Transfer not found with id: " + transferId));
    }

    @Transactional(readOnly = true)
    public java.util.List<TransferDto> list(Long userId) {
        return transferRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(TransferDto::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public java.util.List<TransferDto> listAll() {
        return transferRepository.findAll(org.springframework.data.domain.PageRequest.of(0, 500))
                .map(TransferDto::from).stream().toList();
    }

    private BigDecimal computeFee(BigDecimal amount) {
        BigDecimal percent = properties.transfer().feePercent();
        return amount.multiply(percent).divide(BigDecimal.valueOf(100), MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private void requireActive(Wallet wallet) {
        if (wallet.getStatus() != Wallet.Status.ACTIVE) {
            throw new BadRequestException("Wallet " + wallet.getId() + " is not active");
        }
    }

    private Map<String, Object> eventPayload(Transfer t) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("transferId", t.getId());
        payload.put("fromWalletId", t.getFromWallet().getId());
        payload.put("toWalletId", t.getToWallet().getId());
        payload.put("amount", t.getAmount());
        payload.put("currency", t.getCurrency());
        payload.put("convertedAmount", t.getConvertedAmount());
        payload.put("fxRate", t.getFxRate());
        payload.put("fee", t.getFee());
        payload.put("status", t.getStatus().name());
        payload.put("userId", t.getUser().getId());
        return payload;
    }
}
