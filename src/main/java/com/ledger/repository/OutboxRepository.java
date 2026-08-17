package com.ledger.repository;

import com.ledger.model.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Claims a batch of pending events, atomically removing them from the
     * concurrent pollers via SELECT ... FOR UPDATE SKIP LOCKED.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(value = """
            select * from outbox_events
            where status = 'PENDING' and available_at <= :now
            order by id
            limit :limit
            for update skip locked
            """, nativeQuery = true)
    List<OutboxEvent> lockNextBatch(@Param("now") Instant now, @Param("limit") int limit);

    long countByStatus(OutboxEvent.Status status);

    Optional<OutboxEvent> findFirstByEventTypeOrderByIdDesc(String eventType);
}
