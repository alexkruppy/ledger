package com.ledger.repository;

import com.ledger.model.Transfer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

public interface TransferRepository extends JpaRepository<Transfer, Long> {

    Optional<Transfer> findByIdAndUserId(Long id, Long userId);

    List<Transfer> findByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<Transfer> findByIdempotencyKey(String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Transfer t join fetch t.fromWallet where t.id = :id")
    Optional<Transfer> findByIdForUpdate(@Param("id") Long id);
}
