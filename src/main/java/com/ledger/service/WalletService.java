package com.ledger.service;

import com.ledger.dto.CreateWalletRequest;
import com.ledger.dto.WalletDto;
import com.ledger.exception.BadRequestException;
import com.ledger.exception.ConflictException;
import com.ledger.exception.ResourceNotFoundException;
import com.ledger.model.Wallet;
import com.ledger.repository.UserRepository;
import com.ledger.repository.WalletRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class WalletService {

    private final WalletRepository walletRepository;
    private final UserRepository userRepository;
    private final LedgerService ledgerService;
    private final FxService fxService;

    public WalletService(WalletRepository walletRepository, UserRepository userRepository, LedgerService ledgerService, FxService fxService) {
        this.walletRepository = walletRepository;
        this.userRepository = userRepository;
        this.ledgerService = ledgerService;
        this.fxService = fxService;
    }

    @Transactional
    public WalletDto create(Long userId, CreateWalletRequest request) {
        String currency = request.currency().toUpperCase();
        if (!fxService.supportedCurrencies().contains(currency)) {
            throw new BadRequestException("Unsupported currency: " + currency);
        }
        if (walletRepository.existsByUserIdAndCurrency(userId, currency)) {
            throw new ConflictException("Wallet for " + currency + " already exists");
        }
        Wallet wallet = new Wallet();
        wallet.setUser(userRepository.getReferenceById(userId));
        wallet.setCurrency(currency);
        walletRepository.save(wallet);
        return WalletDto.of(wallet, BigDecimal.ZERO);
    }

    @Transactional(readOnly = true)
    public List<WalletDto> list(Long userId) {
        return walletRepository.findByUserIdOrderByIdAsc(userId).stream()
                .map(w -> WalletDto.of(w, ledgerService.currentBalance(w)))
                .toList();
    }

    @Transactional(readOnly = true)
    public WalletDto get(Long userId, Long walletId) {
        Wallet wallet = findOwned(userId, walletId);
        return WalletDto.of(wallet, ledgerService.currentBalance(wallet));
    }

    @Transactional(readOnly = true)
    public Wallet requireOwned(Long userId, Long walletId) {
        return findOwned(userId, walletId);
    }

    public Wallet findOwned(Long userId, Long walletId) {
        return walletRepository.findByIdAndUserId(walletId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found with id: " + walletId));
    }
}
