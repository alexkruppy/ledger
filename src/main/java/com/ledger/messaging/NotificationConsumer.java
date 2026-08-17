package com.ledger.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Notification consumer demonstrating the retry-topic + DLT pattern:
 * <p>{@code ledger.notifications} → on failure → {@code ledger.notifications.retry}
 * → on failure → {@code ledger.notifications.dlt}.
 * <p>Consumers must be idempotent because outbox delivery is at-least-once.
 */
@Component
public class NotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final Counter consumed;
    private final Counter failed;

    public NotificationConsumer(KafkaTemplate<String, Object> kafkaTemplate, MeterRegistry meterRegistry) {
        this.kafkaTemplate = kafkaTemplate;
        this.consumed = Counter.builder("ledger.notifications.consumed").register(meterRegistry);
        this.failed = Counter.builder("ledger.notifications.failed").register(meterRegistry);
    }

    @KafkaListener(topics = KafkaTopics.NOTIFICATIONS)
    public void onEvent(OutboxMessage message, Acknowledgment ack) {
        try {
            handle(message);
            consumed.increment();
            ack.acknowledge();
        } catch (Exception e) {
            failed.increment();
            log.warn("Notification processing failed for event {}: {}", message.eventId(), e.getMessage());
            forward(message, KafkaTopics.retryOf(KafkaTopics.NOTIFICATIONS));
            ack.acknowledge();
        }
    }

    @KafkaListener(topics = KafkaTopics.NOTIFICATIONS + KafkaTopics.RETRY_SUFFIX)
    public void onRetry(OutboxMessage message, Acknowledgment ack) {
        try {
            handle(message);
            consumed.increment();
            ack.acknowledge();
        } catch (Exception e) {
            failed.increment();
            log.error("Notification retry failed for event {}, moving to DLT: {}", message.eventId(), e.getMessage());
            forward(message, KafkaTopics.dltOf(KafkaTopics.NOTIFICATIONS));
            ack.acknowledge();
        }
    }

    @KafkaListener(topics = KafkaTopics.NOTIFICATIONS + KafkaTopics.DLT_SUFFIX)
    public void onDlt(OutboxMessage message, Acknowledgment ack) {
        log.error("Notification in DLT: eventId={} type={} aggregateId={}", message.eventId(),
                message.eventType(), message.aggregateId());
        ack.acknowledge();
    }

    private void handle(OutboxMessage message) {
        log.info("Notification: eventType={} aggregateId={} payload={}", message.eventType(),
                message.aggregateId(), message.payload());
        if ("TRANSFER_COMPLETED".equals(message.eventType())
                && message.payload() instanceof java.util.Map<?, ?> map
                && "fail-on-purpose".equals(map.get("status"))) {
            throw new IllegalStateException("Simulated consumer failure");
        }
    }

    private void forward(OutboxMessage message, String topic) {
        kafkaTemplate.send(topic, message.aggregateId(), message);
    }
}
