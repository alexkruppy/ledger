package com.ledger.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledger.model.OutboxEvent;
import com.ledger.repository.OutboxRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxServiceTest {

    @Mock private OutboxRepository outboxRepository;

    private final OutboxService outboxService = new OutboxService(outboxRepository, new ObjectMapper());

    @Test
    void emitSavesEventWithSerializedPayload() {
        when(outboxRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> payload = Map.of("key", "value", "number", 42);
        outboxService.emit("transfer", "123", "TRANSFER_COMPLETED", payload);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        OutboxEvent saved = captor.getValue();

        assertThat(saved.getAggregateType()).isEqualTo("transfer");
        assertThat(saved.getAggregateId()).isEqualTo("123");
        assertThat(saved.getEventType()).isEqualTo("TRANSFER_COMPLETED");
        assertThat(saved.getPayload()).contains("\"key\"").contains("\"value\"");
        assertThat(saved.getStatus()).isEqualTo(OutboxEvent.Status.PENDING);
    }
}
