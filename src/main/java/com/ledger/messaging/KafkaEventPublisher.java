package com.ledger.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledger.model.OutboxEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Publishes outbox events to Kafka with acks=all (guaranteed delivery).
 * The send is awaited so the poller can mark the event PUBLISHED only after
 * the broker acknowledged the record.
 */
@Component
public class KafkaEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(5);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final Counter publishedCounter;
    private final Counter publishErrors;

    public KafkaEventPublisher(KafkaTemplate<String, Object> kafkaTemplate,
                               ObjectMapper objectMapper,
                               MeterRegistry meterRegistry) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.publishedCounter = Counter.builder("ledger.outbox.published").register(meterRegistry);
        this.publishErrors = Counter.builder("ledger.outbox.errors").register(meterRegistry);
    }

    public void publish(OutboxEvent event) {
        String topic = KafkaTopics.topicFor(event.getAggregateType());
        OutboxMessage message = new OutboxMessage(
                String.valueOf(event.getId()),
                event.getAggregateType(),
                event.getAggregateId(),
                event.getEventType(),
                parsePayload(event.getPayload()),
                event.getCreatedAt());
        try {
            kafkaTemplate.send(topic, event.getAggregateId(), message).get(SEND_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            publishedCounter.increment();
            log.info("Outbox event {} -> topic {} (type {})", event.getId(), topic, event.getEventType());
        } catch (Exception e) {
            publishErrors.increment();
            throw new IllegalStateException("Failed to publish outbox event " + event.getId() + " to " + topic, e);
        }
    }

    private Object parsePayload(String json) {
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            log.warn("Unparseable outbox payload for event; sending raw", e);
            return json;
        }
    }
}
