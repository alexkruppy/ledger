package com.ledger.controller;

import com.ledger.dto.CreateWalletRequest;
import com.ledger.dto.LedgerEntryDto;
import com.ledger.dto.PageResponse;
import com.ledger.dto.WalletDto;
import com.ledger.model.User;
import com.ledger.repository.LedgerEntryRepository;
import com.ledger.service.WalletService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/wallets")
@Tag(name = "Wallets", description = "Multi-currency wallet management and ledger history")
public class WalletController {

    private final WalletService walletService;
    private final LedgerEntryRepository ledgerEntryRepository;

    public WalletController(WalletService walletService, LedgerEntryRepository ledgerEntryRepository) {
        this.walletService = walletService;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    @PostMapping
    @Operation(summary = "Create a wallet in a given currency")
    public ResponseEntity<WalletDto> create(@AuthenticationPrincipal User user,
                                            @Valid @RequestBody CreateWalletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(walletService.create(user.getId(), request));
    }

    @GetMapping
    @Operation(summary = "List my wallets with current balances")
    public ResponseEntity<List<WalletDto>> list(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(walletService.list(user.getId()));
    }

    @GetMapping("/{walletId}")
    @Operation(summary = "Get a single wallet with its balance")
    public ResponseEntity<WalletDto> get(@AuthenticationPrincipal User user, @PathVariable Long walletId) {
        return ResponseEntity.ok(walletService.get(user.getId(), walletId));
    }

    @GetMapping("/{walletId}/ledger")
    @Operation(summary = "Paged event-sourced ledger of a wallet")
    public ResponseEntity<PageResponse<LedgerEntryDto>> ledger(@AuthenticationPrincipal User user,
                                                               @PathVariable Long walletId,
                                                               @RequestParam(defaultValue = "0") int page,
                                                               @RequestParam(defaultValue = "20") int size) {
        walletService.requireOwned(user.getId(), walletId);
        PageRequest pageable = PageRequest.of(page, Math.min(size, 100));
        return ResponseEntity.ok(PageResponse.of(ledgerEntryRepository.findByWalletIdOrderByCreatedAtDescIdDesc(walletId, pageable), LedgerEntryDto::from));
    }
}
