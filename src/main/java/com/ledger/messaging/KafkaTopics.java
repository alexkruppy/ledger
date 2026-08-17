package com.ledger.messaging;

public final class KafkaTopics {

    private KafkaTopics() {
    }

    public static final String TRANSFERS = "ledger.transfers";
    public static final String PAYMENTS = "ledger.payments";
    public static final String WALLETS = "ledger.wallets";
    public static final String NOTIFICATIONS = "ledger.notifications";

    public static final String RETRY_SUFFIX = ".retry";
    public static final String DLT_SUFFIX = ".dlt";

    public static String retryOf(String topic) {
        return topic + RETRY_SUFFIX;
    }

    public static String dltOf(String topic) {
        return topic + DLT_SUFFIX;
    }

    /** Routes an outbox event (by aggregate type) to its Kafka topic. */
    public static String topicFor(String aggregateType) {
        return switch (aggregateType) {
            case "transfer" -> TRANSFERS;
            case "payment" -> PAYMENTS;
            case "wallet" -> WALLETS;
            case "notification" -> NOTIFICATIONS;
            default -> NOTIFICATIONS;
        };
    }
}
