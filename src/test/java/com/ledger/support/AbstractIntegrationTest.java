package com.ledger.support;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.ledger.model.LedgerEntry;
import com.ledger.model.User;
import com.ledger.model.Wallet;
import com.ledger.repository.LedgerEntryRepository;
import com.ledger.repository.OutboxRepository;
import com.ledger.repository.PaymentTransactionRepository;
import com.ledger.repository.TransferRepository;
import com.ledger.repository.WalletRepository;
import com.ledger.repository.UserRepository;
import com.ledger.service.FxService;
import com.ledger.service.LedgerService;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = {
        "ledger.outbox.poll-interval-ms=300",
        "ledger.gateway.settle-async=false",
        "ledger.security.refresh-token-store=inmemory",
        "logging.level.com.ledger.messaging=WARN",
        "logging.level.org.apache.kafka=WARN",
        "spring.kafka.bootstrap-servers=localhost:19092",
})
public abstract class AbstractIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    protected static WireMockServer gateway;

    @LocalServerPort
    protected int port;

    @Autowired
    protected UserRepository userRepository;
    @Autowired
    protected WalletRepository walletRepository;
    @Autowired
    protected LedgerEntryRepository ledgerEntryRepository;
    @Autowired
    protected TransferRepository transferRepository;
    @Autowired
    protected PaymentTransactionRepository paymentRepository;
    @Autowired
    protected OutboxRepository outboxRepository;
    @Autowired
    protected LedgerService ledgerService;
    @Autowired
    protected PasswordEncoder passwordEncoder;
    @Autowired
    protected TransactionTemplate tx;
    @Autowired
    protected FxService fxService;

    static {
        gateway = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        gateway.start();
        stubGateway();
    }

    static void stubGateway() {
        gateway.stubFor(post(urlPathEqualTo("/v1/charges"))
                .withRequestBody(matchingJsonPath("$.amount", matching("50(\\.0+)?|49(\\.0+)?|10(\\.0+)?")))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"ext-charge-1\",\"status\":\"APPROVED\"}")));
        gateway.stubFor(post(urlPathEqualTo("/v1/charges"))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withBody("{\"error\":\"Transaction declined by risk rules\"}")));
        gateway.stubFor(post(urlPathEqualTo("/v1/payouts"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"ext-payout-1\",\"status\":\"APPROVED\"}")));
        gateway.stubFor(post(urlPathEqualTo("/v1/payouts"))
                .withRequestBody(matchingJsonPath("$.amount", matching("100(\\.0+)?")))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withBody("{\"error\":\"Bank account declined the payout\"}")));
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("ledger.gateway.base-url", gateway::baseUrl);
    }

    @AfterEach
    void cleanDatabase() {
        tx.executeWithoutResult(s -> {
            ledgerEntryRepository.deleteAll();
            transferRepository.deleteAll();
            paymentRepository.deleteAll();
            outboxRepository.deleteAll();
            walletRepository.deleteAll();
            userRepository.deleteAll();
        });
        seedSystemData();
    }

    private void seedSystemData() {
        tx.executeWithoutResult(s -> {
            String feeEmail = "fees@ledger.internal";
            if (!userRepository.existsByEmailIgnoreCase(feeEmail)) {
                User fees = new User();
                fees.setEmail(feeEmail);
                fees.setPasswordHash("!disabled!");
                fees.setFirstName("Ledger");
                fees.setLastName("Fees");
                fees.setRole(User.Role.SYSTEM);
                fees = userRepository.save(fees);
                for (String currency : fxService.supportedCurrencies()) {
                    if (!walletRepository.existsByUserIdAndCurrency(fees.getId(), currency)) {
                        Wallet w = new Wallet();
                        w.setUser(fees);
                        w.setCurrency(currency);
                        walletRepository.save(w);
                    }
                }
            }
        });
    }

    protected User createUser(String email) {
        return tx.execute(s -> {
            User user = new User();
            user.setEmail(email);
            user.setPasswordHash(passwordEncoder.encode("secret123"));
            user.setFirstName("Test");
            user.setRole(User.Role.USER);
            return userRepository.save(user);
        });
    }

    protected Wallet createWallet(Long userId, String currency) {
        return tx.execute(s -> {
            Wallet wallet = new Wallet();
            wallet.setUser(userRepository.getReferenceById(userId));
            wallet.setCurrency(currency);
            return walletRepository.save(wallet);
        });
    }

    protected void seedBalance(Long walletId, BigDecimal amount) {
        tx.executeWithoutResult(s -> {
            Wallet wallet = walletRepository.findById(walletId).orElseThrow();
            ledgerService.post(wallet, LedgerEntry.EntryType.CREDIT, amount,
                    "seed:" + java.util.UUID.randomUUID(), "test seed");
        });
    }

    protected static void await(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("Condition not met within " + timeout);
    }

    protected static String baseUrl(int port) {
        return "http://localhost:" + port;
    }

    protected Instant now() {
        return Instant.now();
    }
}
