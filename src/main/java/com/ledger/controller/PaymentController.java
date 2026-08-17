package com.ledger.controller;

import com.ledger.config.LedgerProperties;
import com.ledger.dto.PaymentDto;
import com.ledger.dto.TopUpRequest;
import com.ledger.dto.WithdrawRequest;
import com.ledger.model.User;
import com.ledger.security.RateLimitService;
import com.ledger.service.IdempotencyService;
import com.ledger.service.PaymentSagaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;

@RestController
@RequestMapping("/api/payments")
@Tag(name = "Payments", description = "Saga-orchestrated top-up and withdrawal via the external gateway")
public class PaymentController {

    private final PaymentSagaService sagaService;
    private final IdempotencyService idempotencyService;
    private final RateLimitService rateLimitService;
    private final LedgerProperties properties;

    public PaymentController(PaymentSagaService sagaService,
                             IdempotencyService idempotencyService,
                             RateLimitService rateLimitService,
                             LedgerProperties properties) {
        this.sagaService = sagaService;
        this.idempotencyService = idempotencyService;
        this.rateLimitService = rateLimitService;
        this.properties = properties;
    }

    @PostMapping("/wallets/{walletId}/topup")
    @Operation(summary = "Top up a wallet via the external acquiring gateway (saga)")
    public ResponseEntity<?> topUp(@AuthenticationPrincipal User user,
                                   @PathVariable Long walletId,
                                   @Valid @RequestBody TopUpRequest request,
                                   @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        rateLimitService.check("payment:" + user.getId(),
                properties.rateLimit().transferPerMinute(), Duration.ofMinutes(1));
        return idempotencyService.execute(user.getId(), idempotencyKey,
                () -> ResponseEntity.ok(sagaService.topUp(user, walletId, request, idempotencyKey)));
    }

    @PostMapping("/wallets/{walletId}/withdraw")
    @Operation(summary = "Withdraw from a wallet to an external bank account (saga with compensation)")
    public ResponseEntity<?> withdraw(@AuthenticationPrincipal User user,
                                      @PathVariable Long walletId,
                                      @Valid @RequestBody WithdrawRequest request,
                                      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        rateLimitService.check("payment:" + user.getId(),
                properties.rateLimit().transferPerMinute(), Duration.ofMinutes(1));
        return idempotencyService.execute(user.getId(), idempotencyKey,
                () -> ResponseEntity.ok(sagaService.withdraw(user, walletId, request, idempotencyKey)));
    }

    @GetMapping
    @Operation(summary = "My payments")
    public ResponseEntity<List<PaymentDto>> list(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(sagaService.list(user.getId()));
    }

    @GetMapping("/{paymentId}")
    @Operation(summary = "Get one payment")
    public ResponseEntity<PaymentDto> get(@AuthenticationPrincipal User user, @PathVariable Long paymentId) {
        return ResponseEntity.ok(sagaService.get(user.getId(), paymentId));
    }
}
