package com.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledger.dto.AuthResponse;
import com.ledger.dto.PaymentDto;
import com.ledger.dto.TransferDto;
import com.ledger.dto.UserDto;
import com.ledger.messaging.OutboxPoller;
import com.ledger.model.OutboxEvent;
import com.ledger.model.Transfer;
import com.ledger.model.Wallet;
import com.ledger.support.AbstractIntegrationTest;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Single integration test class sharing ONE Spring Boot context with all
 * containers (Postgres, Redis, Kafka). Inner @Nested classes keep tests
 * organized while sharing the same context — only ONE context boot,
 * minimizing Docker memory pressure on CI.
 */
class IntegrationTest extends AbstractIntegrationTest {

    private final TestRestTemplate rest = new TestRestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private OutboxPoller outboxPoller;

    // ==================== AUTH ====================

    @Nested
    @DisplayName("Auth")
    class Auth {

        @Test
        void registerLoginRefreshAndReuseDetection() {
            String email = "user-" + UUID.randomUUID() + "@test.com";

            ResponseEntity<AuthResponse> reg = rest.postForEntity(baseUrl(port) + "/api/auth/register",
                    Map.of("email", email, "password", "secret123", "firstName", "Test", "lastName", "User"),
                    AuthResponse.class);
            assertThat(reg.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(reg.getBody()).isNotNull();
            assertThat(reg.getBody().accessToken()).isNotBlank();
            assertThat(reg.getBody().refreshToken()).isNotBlank();

            ResponseEntity<UserDto> me = rest.exchange(baseUrl(port) + "/api/auth/me", HttpMethod.GET,
                    bearer(reg.getBody().accessToken()), UserDto.class);
            assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(me.getBody().email()).isEqualTo(email);

            ResponseEntity<AuthResponse> refreshed = rest.postForEntity(baseUrl(port) + "/api/auth/refresh",
                    Map.of("refreshToken", reg.getBody().refreshToken()), AuthResponse.class);
            assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(refreshed.getBody().refreshToken()).isNotEqualTo(reg.getBody().refreshToken());

            ResponseEntity<String> reuse = rest.postForEntity(baseUrl(port) + "/api/auth/refresh",
                    Map.of("refreshToken", reg.getBody().refreshToken()), String.class);
            assertThat(reuse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        void loginWithWrongPasswordRejected() {
            createUser("badpass-" + UUID.randomUUID() + "@test.com");
            ResponseEntity<String> login = rest.postForEntity(baseUrl(port) + "/api/auth/login",
                    Map.of("email", "missing@test.com", "password", "wrong"), String.class);
            assertThat(login.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    // ==================== TRANSFER ====================

    @Nested
    @DisplayName("Transfer")
    class TransferTests {

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

            @SuppressWarnings("unchecked")
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
    }

    // ==================== SAGA / PAYMENT ====================

    @Nested
    @DisplayName("Saga / Payment")
    class Saga {

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

    // ==================== OUTBOX / KAFKA ====================

    @Nested
    @DisplayName("Outbox / Kafka")
    class Outbox {

        @Test
        void transferEventIsPersistedToOutboxAndPublishedToKafka() throws Exception {
            var userA = createUser("out-a-" + UUID.randomUUID() + "@test.com");
            var userB = createUser("out-b-" + UUID.randomUUID() + "@test.com");
            Wallet from = createWallet(userA.getId(), "EUR");
            Wallet to = createWallet(userB.getId(), "EUR");
            seedBalance(from.getId(), new BigDecimal("100.00"));

            ResponseEntity<AuthResponse> login = rest.postForEntity(baseUrl(port) + "/api/auth/login",
                    Map.of("email", userA.getEmail(), "password", "secret123"), AuthResponse.class);
            String token = login.getBody().accessToken();

            try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps())) {
                consumer.subscribe(List.of("ledger.transfers"));

                HttpHeaders headers = new HttpHeaders();
                headers.setBearerAuth(token);
                headers.set("Idempotency-Key", UUID.randomUUID().toString());
                headers.set("Content-Type", "application/json");
                HttpEntity<Map<String, Object>> entity = new HttpEntity<>(Map.of(
                        "fromWalletId", from.getId(),
                        "toWalletId", to.getId(),
                        "amount", "10.00"), headers);
                ResponseEntity<String> resp = rest.exchange(baseUrl(port) + "/api/transfers",
                        HttpMethod.POST, entity, String.class);
                assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();

                OutboxEvent event = outboxRepository.findFirstByEventTypeOrderByIdDesc("TRANSFER_COMPLETED")
                        .orElseThrow();
                assertThat(event.getStatus()).isEqualTo(OutboxEvent.Status.PENDING);

                outboxPoller.publishPending();

                OutboxEvent published = outboxRepository.findById(event.getId()).orElseThrow();
                assertThat(published.getStatus()).isEqualTo(OutboxEvent.Status.PUBLISHED);
                assertThat(published.getPublishedAt()).isNotNull();

                await(() -> {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                    for (ConsumerRecord<String, String> record : records) {
                        try {
                            String eventId = objectMapper.readTree(record.value()).get("eventId").asText();
                            if (eventId.equals(String.valueOf(event.getId()))) {
                                return true;
                            }
                        } catch (Exception ignored) {
                        }
                    }
                    return false;
                }, Duration.ofSeconds(10));
            }
        }

        private Properties consumerProps() {
            Properties props = new Properties();
            props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrap());
            props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
            props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
            props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
            props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
            return props;
        }
    }

    // ==================== HELPERS ====================

    private HttpEntity<Void> bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(headers);
    }

    private HttpEntity<Map<String, Object>> bearer(String token, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set("Content-Type", "application/json");
        return new HttpEntity<>(body, headers);
    }
}
