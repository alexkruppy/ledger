package com.ledger.repository;

import com.ledger.model.Wallet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

public interface WalletRepository extends JpaRepository<Wallet, Long> {

    List<Wallet> findByUserIdOrderByIdAsc(Long userId);

    Optional<Wallet> findByIdAndUserId(Long id, Long userId);

    boolean existsByUserIdAndCurrency(Long userId, String currency);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Wallet w join fetch w.user where w.id = :id")
    Optional<Wallet> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Wallet w join fetch w.user where w.user.email = :email and w.currency = :currency")
    Optional<Wallet> findSystemWallet(@Param("email") String ownerEmail, @Param("currency") String currency);

    @Modifying
    @Query("DELETE FROM Wallet w WHERE w.user.email <> 'fees@ledger.internal'")
    int deleteTestWallets();
}
