package com.ledger.config;

import com.ledger.model.User;
import com.ledger.model.Wallet;
import com.ledger.repository.UserRepository;
import com.ledger.repository.WalletRepository;
import com.ledger.service.FxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds the admin account and creates the system fee-account wallets in every
 * supported currency (required for fee crediting on transfers).
 */
@Component
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final FxService fxService;
    private final PasswordEncoder passwordEncoder;
    private final LedgerProperties properties;

    public DataSeeder(UserRepository userRepository,
                      WalletRepository walletRepository,
                      FxService fxService,
                      PasswordEncoder passwordEncoder,
                      LedgerProperties properties) {
        this.userRepository = userRepository;
        this.walletRepository = walletRepository;
        this.fxService = fxService;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @Override
    @Transactional
    public void run(String... args) {
        seedAdmin();
        seedFeeWallets();
    }

    private void seedAdmin() {
        String email = properties.app().seedAdminEmail();
        if (!userRepository.existsByEmailIgnoreCase(email)) {
            User admin = new User();
            admin.setEmail(email);
            admin.setPasswordHash(passwordEncoder.encode(properties.app().seedAdminPassword()));
            admin.setFirstName("Admin");
            admin.setRole(User.Role.ADMIN);
            userRepository.save(admin);
            log.info("Seeded admin account: {}", email);
        }
    }

    private void seedFeeWallets() {
        String ownerEmail = properties.app().feeWalletOwnerEmail();
        User owner = userRepository.findByEmailIgnoreCase(ownerEmail).orElseGet(() -> {
            User fees = new User();
            fees.setEmail(ownerEmail);
            fees.setPasswordHash("!disabled!");
            fees.setFirstName("Ledger");
            fees.setLastName("Fees");
            fees.setRole(User.Role.SYSTEM);
            return userRepository.save(fees);
        });
        for (String currency : fxService.supportedCurrencies()) {
            if (!walletRepository.existsByUserIdAndCurrency(owner.getId(), currency)) {
                Wallet wallet = new Wallet();
                wallet.setUser(owner);
                wallet.setCurrency(currency);
                walletRepository.save(wallet);
            }
        }
        log.info("Seeded fee-account wallets for {}", ownerEmail);
    }
}
