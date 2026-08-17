package com.ledger.controller;

import com.ledger.config.LedgerProperties;
import com.ledger.dto.TransferDto;
import com.ledger.dto.TransferRequest;
import com.ledger.model.User;
import com.ledger.security.RateLimitService;
import com.ledger.service.IdempotencyService;
import com.ledger.service.TransferService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;

@RestController
@RequestMapping("/api/transfers")
@Tag(name = "Transfers", description = "Idempotent internal P2P transfers between wallets")
public class TransferController {

    private final TransferService transferService;
    private final IdempotencyService idempotencyService;
    private final RateLimitService rateLimitService;
    private final LedgerProperties properties;

    public TransferController(TransferService transferService,
                              IdempotencyService idempotencyService,
                              RateLimitService rateLimitService,
                              LedgerProperties properties) {
        this.transferService = transferService;
        this.idempotencyService = idempotencyService;
        this.rateLimitService = rateLimitService;
        this.properties = properties;
    }

    @PostMapping
    @Operation(summary = "Execute a transfer (Idempotency-Key header makes it safe to retry)")
    public ResponseEntity<?> transfer(@AuthenticationPrincipal User user,
                                      @Valid @RequestBody TransferRequest request,
                                      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        rateLimitService.check("transfer:" + user.getId(),
                properties.rateLimit().transferPerMinute(), Duration.ofMinutes(1));
        return idempotencyService.execute(user.getId(), idempotencyKey,
                () -> ResponseEntity.ok(transferService.transfer(user, request)));
    }

    @GetMapping
    @Operation(summary = "My transfers")
    public ResponseEntity<List<TransferDto>> list(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(transferService.list(user.getId()));
    }

    @GetMapping("/{transferId}")
    @Operation(summary = "Get one transfer")
    public ResponseEntity<TransferDto> get(@AuthenticationPrincipal User user, @PathVariable Long transferId) {
        return ResponseEntity.ok(transferService.get(user.getId(), transferId));
    }
}
