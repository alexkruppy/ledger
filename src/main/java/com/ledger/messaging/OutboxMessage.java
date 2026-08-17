package com.ledger.messaging;

import java.time.Instant;

/** Envelope written to Kafka topics by the outbox poller. */
public record OutboxMessage(
        String eventId,
        String aggregateType,
        String aggregateId,
        String eventType,
        Object payload,
        Instant occurredAt) {
}
