package com.ledger;

import com.ledger.dto.AuthResponse;
import com.ledger.dto.PaymentDto;
import com.ledger.model.Wallet;
import com.ledger.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SagaIntegrationTest extends AbstractIntegrationTest {

    private final TestRestTemplate rest = new TestRestTemplate();
    private String token;
    private Wallet wallet;

    @BeforeEach
    void setUp() {
        var user = createUser("pay-" + UUID.randomUUID() + "@test.com");
        wallet = createWallet(user.getId(), "EUR");
        ResponseEntity<AuthResponse> login = rest.postForEntity(baseUrl(port) + "/api/auth/login",
                Map.of("email", user.getEmail(), "password", "secret123"), AuthResponse.class);
        token = login.getBody().accessToken();
    }

    @Test
    void topUpCompletesWhenGatewayApproves() {
        ResponseEntity<PaymentDto> resp = post("/topup", "50.00");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().status()).isEqualTo("COMPLETED");
        assertThat(resp.getBody().externalPaymentId()).isEqualTo("ext-charge-1");
        assertThat(balance()).isEqualByComparingTo(new BigDecimal("50.00000000"));
    }

    @Test
    void topUpRollsBackWhenGatewayDeclines() {
        ResponseEntity<PaymentDto> resp = post("/topup", "100.00");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().status()).isEqualTo("ROLLED_BACK");
        assertThat(balance()).isEqualByComparingTo(new BigDecimal("0.00000000"));
    }

    @Test
    void withdrawalDebitsOnSuccess() {
        seedBalance(wallet.getId(), new BigDecimal("100.00"));
        ResponseEntity<PaymentDto> resp = post("/withdraw", "30.00");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().status()).isEqualTo("COMPLETED");
        assertThat(balance()).isEqualByComparingTo(new BigDecimal("70.00000000"));
    }

    @Test
    void failedWithdrawalIsCompensatedAndFundsReturned() {
        seedBalance(wallet.getId(), new BigDecimal("100.00"));
        ResponseEntity<PaymentDto> resp = post("/withdraw", "100.00");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().status()).isEqualTo("ROLLED_BACK");
        assertThat(resp.getBody().failureReason()).isNotBlank();
        // Compensating credit restored the reserved funds.
        assertThat(balance()).isEqualByComparingTo(new BigDecimal("100.00000000"));
    }

    private ResponseEntity<PaymentDto> post(String path, String amount) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        headers.set("Content-Type", "application/json");
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(Map.of("amount", amount), headers);
        return rest.exchange(baseUrl(port) + "/api/payments/wallets/" + wallet.getId() + path,
                HttpMethod.POST, entity, PaymentDto.class);
    }

    private BigDecimal balance() {
        return tx.execute(s -> walletRepository.findById(wallet.getId()).orElseThrow().getBalance());
    }
}
