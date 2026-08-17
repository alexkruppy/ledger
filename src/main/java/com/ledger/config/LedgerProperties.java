package com.ledger.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;

@ConfigurationProperties(prefix = "ledger")
public record LedgerProperties(
        Security security,
        Idempotency idempotency,
        Transfer transfer,
        RateLimit rateLimit,
        Outbox outbox,
        Gateway gateway,
        Fx fx,
        App app) {

    public record Security(
            String jwtSecret,
            Duration accessTokenTtl,
            Duration refreshTokenTtl,
            String refreshTokenStore) {
    }

    public record Idempotency(Duration ttl) {
    }

    public record Transfer(BigDecimal feePercent, BigDecimal minAmount, BigDecimal maxAmount) {
    }

    public record RateLimit(int loginPerMinute, int transferPerMinute) {
    }

    public record Outbox(long pollIntervalMs, int batchSize, int maxAttempts) {
    }

    public record Gateway(String baseUrl, int connectTimeoutMs, int readTimeoutMs, boolean settleAsync) {
    }

    public record Fx(String pivotCurrency) {
    }

    public record App(String feeWalletOwnerEmail, String seedAdminEmail, String seedAdminPassword) {
    }
}
