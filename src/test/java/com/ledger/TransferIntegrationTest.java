package com.ledger;

import com.ledger.dto.AuthResponse;
import com.ledger.dto.TransferDto;
import com.ledger.model.Transfer;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class TransferIntegrationTest extends AbstractIntegrationTest {

    private final TestRestTemplate rest = new TestRestTemplate();
    private String tokenA;
    private Wallet walletA;
    private Wallet walletB;
    private long walletBId;

    @BeforeEach
    void setUp() {
        var userA = createUser("alice-" + UUID.randomUUID() + "@test.com");
        var userB = createUser("bob-" + UUID.randomUUID() + "@test.com");
        walletA = createWallet(userA.getId(), "EUR");
        walletB = createWallet(userB.getId(), "USD");
        walletBId = walletB.getId();
        seedBalance(walletA.getId(), new BigDecimal("100.00"));

        ResponseEntity<AuthResponse> login = rest.postForEntity(baseUrl(port) + "/api/auth/login",
                Map.of("email", userA.getEmail(), "password", "secret123"), AuthResponse.class);
        tokenA = login.getBody().accessToken();
    }

    @Test
    void crossCurrencyTransferMovesConvertedAmountAndPostsLedgerEntries() {
        ResponseEntity<TransferDto> resp = postTransfer(walletA.getId(), walletBId, "50.00", UUID.randomUUID().toString());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        TransferDto transfer = resp.getBody();
        assertThat(transfer.status()).isEqualTo("COMPLETED");
        assertThat(transfer.amount()).isEqualByComparingTo("50.00");
        assertThat(transfer.fxRate()).isNotNull();
        // EUR -> USD: 50 * 1.084 = 54.20
        assertThat(transfer.convertedAmount()).isEqualByComparingTo(new BigDecimal("54.20000000"));

        assertThat(walletBalance(walletA.getId())).isEqualByComparingTo(new BigDecimal("50.00000000"));
        assertThat(walletBalance(walletBId)).isEqualByComparingTo(new BigDecimal("54.20000000"));

        assertThat(ledgerEntryRepository.existsByWalletIdAndOperationKey(walletA.getId(), "transfer:" + transfer.id())).isTrue();
        assertThat(ledgerEntryRepository.existsByWalletIdAndOperationKey(walletBId, "transfer:" + transfer.id())).isTrue();
    }

    @Test
    void idempotencyKeyReturnsCachedResultAndDoesNotReExecute() {
        String key = UUID.randomUUID().toString();
        ResponseEntity<TransferDto> first = postTransfer(walletA.getId(), walletBId, "20.00", key);
        ResponseEntity<TransferDto> second = postTransfer(walletA.getId(), walletBId, "20.00", key);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().id()).isEqualTo(first.getBody().id());
        assertThat(transferRepository.count()).isEqualTo(1);
        assertThat(walletBalance(walletA.getId())).isEqualByComparingTo(new BigDecimal("80.00000000"));
    }

    @Test
    void insufficientFundsRejected() {
        ResponseEntity<String> resp = rest.exchange(baseUrl(port) + "/api/transfers", HttpMethod.POST,
                bearer(tokenA, Map.of(
                        "fromWalletId", walletA.getId(),
                        "toWalletId", walletBId,
                        "amount", "1000.00")),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(transferRepository.count()).isZero();
        assertThat(walletBalance(walletA.getId())).isEqualByComparingTo(new BigDecimal("100.00000000"));
    }

    @Test
    void concurrentTransfersFromSameWalletPreventDoubleSpend() throws Exception {
        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        String key1 = UUID.randomUUID().toString();
        String key2 = UUID.randomUUID().toString();

        Future<Transfer.Status>[] results = new Future[threads];
        for (int i = 0; i < threads; i++) {
            final String key = i == 0 ? key1 : key2;
            results[i] = pool.submit(() -> {
                start.await();
                try {
                    ResponseEntity<TransferDto> resp = postTransfer(walletA.getId(), walletBId, "60.00", key);
                    return resp.getBody() == null ? Transfer.Status.FAILED : Transfer.Status.valueOf(resp.getBody().status());
                } catch (Exception e) {
                    return Transfer.Status.FAILED;
                }
            });
        }
        start.countDown();

        int completed = 0;
        int failed = 0;
        for (Future<Transfer.Status> result : results) {
            if (result.get() == Transfer.Status.COMPLETED) {
                completed++;
            } else {
                failed++;
            }
        }
        pool.shutdown();

        assertThat(completed).isEqualTo(1);
        assertThat(failed).isEqualTo(1);
        // Exactly 60 spent: 100 - 60 = 40
        assertThat(walletBalance(walletA.getId())).isEqualByComparingTo(new BigDecimal("40.00000000"));
        assertThat(transferRepository.count()).isEqualTo(1);
    }

    private ResponseEntity<TransferDto> postTransfer(Long from, Long to, String amount, String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tokenA);
        headers.set("Idempotency-Key", key);
        headers.set("Content-Type", "application/json");
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(Map.of(
                "fromWalletId", from,
                "toWalletId", to,
                "amount", amount), headers);
        return rest.exchange(baseUrl(port) + "/api/transfers", HttpMethod.POST, entity, TransferDto.class);
    }

    private BigDecimal walletBalance(Long walletId) {
        return tx.execute(s -> walletRepository.findById(walletId).orElseThrow().getBalance());
    }

    private HttpEntity<Map<String, Object>> bearer(String token, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set("Content-Type", "application/json");
        return new HttpEntity<>(body, headers);
    }
}
