package com.ledger.gateway;

import com.ledger.config.LedgerProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Client for the simulated external acquiring gateway (mock-gateway service).
 * The gateway can answer slowly or fail entirely — those failures are treated
 * as saga failures and trigger compensation.
 */
@Component
public class MockGatewayClient {

    private static final Logger log = LoggerFactory.getLogger(MockGatewayClient.class);

    private final RestClient restClient;
    private final Timer gatewayTimer;

    public MockGatewayClient(LedgerProperties properties, MeterRegistry meterRegistry) {
        this.gatewayTimer = Timer.builder("ledger.gateway.latency")
                .description("External gateway round-trip latency")
                .publishPercentileHistogram(true)
                .register(meterRegistry);
        this.restClient = RestClient.builder()
                .baseUrl(properties.gateway().baseUrl())
                .requestFactory(RestClientFactory.withTimeouts(
                        Duration.ofMillis(properties.gateway().connectTimeoutMs()),
                        Duration.ofMillis(properties.gateway().readTimeoutMs())))
                .defaultHeader("X-Request-Id", UUID.randomUUID().toString())
                .build();
    }

    /** Card charge (wallet top-up). */
    public GatewayResponse charge(String currency, String reference, java.math.BigDecimal amount) {
        return call("/v1/charges", new GatewayChargeRequest(amount, currency, reference));
    }

    /** Bank payout (wallet withdrawal). */
    public GatewayResponse payout(String currency, String reference, java.math.BigDecimal amount) {
        return call("/v1/payouts", new GatewayChargeRequest(amount, currency, reference));
    }

    private GatewayResponse call(String path, GatewayChargeRequest body) {
        long start = System.nanoTime();
        try {
            GatewayResponse response = restClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw new GatewayClientException("Gateway returned HTTP " + res.getStatusCode());
                    })
                    .body(GatewayResponse.class);
            if (response == null) {
                throw new GatewayClientException("Gateway returned an empty response");
            }
            return response;
        } catch (GatewayClientException e) {
            recordDuration(start);
            throw e;
        } catch (Exception e) {
            recordDuration(start);
            log.warn("Gateway call to {} failed: {}", path, e.getMessage());
            throw new GatewayClientException("Gateway unreachable: " + e.getMessage(), e);
        }
    }

    private void recordDuration(long startNanos) {
        gatewayTimer.record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
    }
}
