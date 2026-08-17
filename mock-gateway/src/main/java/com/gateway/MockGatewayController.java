package com.gateway;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Simulated external acquiring gateway. Mimics a real-world bank integration:
 * <ul>
 *   <li>non-deterministic latency (200–900 ms),</li>
 *   <li>failures for amounts divisible by 100 (e.g. 100.00 → 500),</li>
 *   <li>occasional random 5% failures.</li>
 * </ul>
 */
@RestController
public class MockGatewayController {

    private static final Logger log = LoggerFactory.getLogger(MockGatewayController.class);

    @PostMapping("/v1/charges")
    public Map<String, String> charge(@RequestBody ChargeRequest request) {
        return process("CHARGE", request);
    }

    @PostMapping("/v1/payouts")
    public Map<String, String> payout(@RequestBody ChargeRequest request) {
        return process("PAYOUT", request);
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    private Map<String, String> process(String operation, ChargeRequest request) {
        simulateLatency();
        if (request.amount().remainder(BigDecimal.valueOf(100)).signum() == 0) {
            log.warn("{} {} refused: amount {} is flagged for failure", operation, request.reference(), request.amount());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Transaction declined by risk rules");
        }
        if (ThreadLocalRandom.current().nextInt(100) < 5) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Acquiring service temporarily unavailable");
        }
        String id = "ext-" + UUID.randomUUID().toString().substring(0, 12);
        log.info("{} {} authorized: {} {}", operation, id, request.amount(), request.currency());
        return Map.of("id", id, "status", "APPROVED");
    }

    private void simulateLatency() {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(200, 900));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChargeRequest(BigDecimal amount, String currency, String reference) {
    }
}
