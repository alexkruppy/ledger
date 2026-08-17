package com.ledger.repository;

import com.ledger.model.LedgerEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    Page<LedgerEntry> findByWalletIdOrderByCreatedAtDescIdDesc(Long walletId, Pageable pageable);

    Optional<LedgerEntry> findFirstByWalletIdOrderByIdDesc(Long walletId);

    boolean existsByWalletIdAndOperationKey(Long walletId, String operationKey);
}
