package com.ledger.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

/**
 * Extension of {@link AbstractIntegrationTest} that also starts a real Kafka
 * broker via Testcontainers. Only tests that publish / consume Kafka messages
 * (e.g. {@code OutboxIntegrationTest}) should extend this class.
 */
public abstract class AbstractKafkaIntegrationTest extends AbstractIntegrationTest {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.1"))
            .withStartupTimeout(Duration.ofSeconds(180));

    protected static String kafkaBootstrap() {
        return KAFKA.getBootstrapServers();
    }

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }
}
