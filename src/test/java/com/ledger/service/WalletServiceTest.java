package com.ledger.service;

import com.ledger.dto.CreateWalletRequest;
import com.ledger.dto.WalletDto;
import com.ledger.exception.BadRequestException;
import com.ledger.exception.ConflictException;
import com.ledger.exception.ResourceNotFoundException;
import com.ledger.model.User;
import com.ledger.model.Wallet;
import com.ledger.repository.UserRepository;
import com.ledger.repository.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.ledger.support.EntityIdSetter;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WalletServiceTest {

    @Mock private WalletRepository walletRepository;
    @Mock private UserRepository userRepository;
    @Mock private LedgerService ledgerService;
    @Mock private FxService fxService;

    private WalletService walletService;

    @BeforeEach
    void setUp() {
        walletService = new WalletService(walletRepository, userRepository, ledgerService, fxService);
    }

    @Test
    void createWalletSuccess() {
        when(fxService.supportedCurrencies()).thenReturn(List.of("EUR", "USD", "GBP"));
        when(walletRepository.existsByUserIdAndCurrency(10L, "USD")).thenReturn(false);
        when(userRepository.getReferenceById(10L)).thenAnswer(inv -> {
            User u = new User();
            EntityIdSetter.setId(u, 10L);
            return u;
        });
        when(walletRepository.save(any())).thenAnswer(inv -> {
            Wallet w = inv.getArgument(0);
            EntityIdSetter.setId(w, 5L);
            return w;
        });

        WalletDto result = walletService.create(10L, new CreateWalletRequest("usd"));
        assertThat(result).isNotNull();
        verify(walletRepository).save(any());
    }

    @Test
    void createWalletUnsupportedCurrencyThrows() {
        when(fxService.supportedCurrencies()).thenReturn(List.of("EUR", "USD"));
        assertThatThrownBy(() -> walletService.create(10L, new CreateWalletRequest("JPY")))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void createWalletDuplicateThrows() {
        when(fxService.supportedCurrencies()).thenReturn(List.of("EUR", "USD"));
        when(walletRepository.existsByUserIdAndCurrency(10L, "EUR")).thenReturn(true);
        assertThatThrownBy(() -> walletService.create(10L, new CreateWalletRequest("EUR")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void listReturnsWalletsWithBalance() {
        Wallet w = new Wallet();
        EntityIdSetter.setId(w, 1L);
        w.setCurrency("EUR");
        when(walletRepository.findByUserIdOrderByIdAsc(10L)).thenReturn(List.of(w));
        when(ledgerService.currentBalance(w)).thenReturn(new BigDecimal("250.00000000"));

        List<WalletDto> result = walletService.list(10L);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).balance()).isEqualByComparingTo("250.00000000");
    }

    @Test
    void getReturnsWallet() {
        Wallet w = new Wallet();
        EntityIdSetter.setId(w, 1L);
        w.setCurrency("EUR");
        User owner = new User();
        EntityIdSetter.setId(owner, 10L);
        w.setUser(owner);
        when(walletRepository.findByIdAndUserId(1L, 10L)).thenReturn(Optional.of(w));
        when(ledgerService.currentBalance(w)).thenReturn(BigDecimal.ZERO);

        WalletDto result = walletService.get(10L, 1L);
        assertThat(result).isNotNull();
    }

    @Test
    void getWalletNotFoundThrows() {
        when(walletRepository.findByIdAndUserId(99L, 10L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> walletService.get(10L, 99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void findOwnedThrowsWhenWrongUser() {
        when(walletRepository.findByIdAndUserId(1L, 10L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> walletService.findOwned(10L, 1L))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
