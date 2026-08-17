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
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = {
        "ledger.outbox.poll-interval-ms=999999999",
        "ledger.gateway.settle-async=false",
        "ledger.security.refresh-token-store=inmemory",
        "logging.level.com.ledger.messaging=WARN",
        "logging.level.org.apache.kafka=WARN",
})
public abstract class AbstractIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.0"))
            .withStartupTimeout(Duration.ofSeconds(240));

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

    @PersistenceContext
    protected EntityManager em;

    @Autowired(required = false)
    protected ThreadPoolTaskScheduler taskScheduler;

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

    protected static String kafkaBootstrap() {
        return KAFKA.getBootstrapServers();
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
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("ledger.gateway.base-url", gateway::baseUrl);
    }

    @AfterEach
    void cleanDatabase() {
        if (taskScheduler != null) {
            taskScheduler.getScheduledExecutor().shutdownNow();
        }
        try { Thread.sleep(200); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        tx.executeWithoutResult(s -> {
            em.createNativeQuery("DELETE FROM ledger_entries").executeUpdate();
            em.createNativeQuery("DELETE FROM transfers").executeUpdate();
            em.createNativeQuery("DELETE FROM payment_transactions").executeUpdate();
            em.createNativeQuery("DELETE FROM outbox_events").executeUpdate();
            em.createNativeQuery("DELETE FROM wallets").executeUpdate();
            em.createNativeQuery("DELETE FROM users WHERE email <> 'fees@ledger.internal'").executeUpdate();
        });
        seedSystemData();
    }

    private void seedSystemData() {
        tx.executeWithoutResult(s -> {
            String feeEmail = "fees@ledger.internal";
            User fees = userRepository.findByEmailIgnoreCase(feeEmail).orElseGet(() -> {
                User u = new User();
                u.setEmail(feeEmail);
                u.setPasswordHash("!disabled!");
                u.setFirstName("Ledger");
                u.setLastName("Fees");
                u.setRole(User.Role.SYSTEM);
                return userRepository.save(u);
            });
            for (String currency : fxService.supportedCurrencies()) {
                if (!walletRepository.existsByUserIdAndCurrency(fees.getId(), currency)) {
                    Wallet w = new Wallet();
                    w.setUser(fees);
                    w.setCurrency(currency);
                    walletRepository.save(w);
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
