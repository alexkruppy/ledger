package com.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledger.dto.AuthResponse;
import com.ledger.model.OutboxEvent;
import com.ledger.model.Wallet;
import com.ledger.support.AbstractIntegrationTest;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxIntegrationTest extends AbstractIntegrationTest {

    private final TestRestTemplate rest = new TestRestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void transferEventIsPersistedToOutboxAndPublishedToKafka() throws Exception {
        var userA = createUser("out-a-" + UUID.randomUUID() + "@test.com");
        var userB = createUser("out-b-" + UUID.randomUUID() + "@test.com");
        Wallet from = createWallet(userA.getId(), "EUR");
        Wallet to = createWallet(userB.getId(), "EUR");
        seedBalance(from.getId(), new BigDecimal("100.00"));

        ResponseEntity<AuthResponse> login = rest.postForEntity(baseUrl(port) + "/api/auth/login",
                Map.of("email", userA.getEmail(), "password", "secret123"), AuthResponse.class);
        String token = login.getBody().accessToken();

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps())) {
            consumer.subscribe(List.of("ledger.transfers"));

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token);
            headers.set("Idempotency-Key", UUID.randomUUID().toString());
            headers.set("Content-Type", "application/json");
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(Map.of(
                    "fromWalletId", from.getId(),
                    "toWalletId", to.getId(),
                    "amount", "10.00"), headers);
            ResponseEntity<String> resp = rest.exchange(baseUrl(port) + "/api/transfers",
                    HttpMethod.POST, entity, String.class);
            assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();

            // The outbox event must be marked PUBLISHED by the poller.
            await(() -> outboxRepository.findFirstByEventTypeOrderByIdDesc("TRANSFER_COMPLETED")
                            .map(e -> e.getStatus() == OutboxEvent.Status.PUBLISHED).orElse(false),
                    Duration.ofSeconds(10));

            OutboxEvent event = outboxRepository.findFirstByEventTypeOrderByIdDesc("TRANSFER_COMPLETED").orElseThrow();

            // And the very same event id must appear on the Kafka topic.
            await(() -> {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    try {
                        String eventId = objectMapper.readTree(record.value()).get("eventId").asText();
                        if (eventId.equals(String.valueOf(event.getId()))) {
                            return true;
                        }
                    } catch (Exception ignored) {
                    }
                }
                return false;
            }, Duration.ofSeconds(10));
        }
    }

    private Properties consumerProps() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrap());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        return props;
    }
}
