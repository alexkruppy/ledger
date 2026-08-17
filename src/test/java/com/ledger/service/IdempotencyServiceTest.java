package com.ledger.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledger.config.LedgerProperties;
import com.ledger.exception.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.ResponseEntity;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    @Mock private RedisTemplate<String, Object> redisTemplate;
    @Mock private ValueOperations<String, Object> valueOperations;

    private IdempotencyService idempotencyService;

    @BeforeEach
    void setUp() {
        LedgerProperties props = new LedgerProperties(
                null,
                new LedgerProperties.Idempotency(Duration.ofMinutes(10)),
                null, null, null, null, null, null);
        idempotencyService = new IdempotencyService(redisTemplate, new ObjectMapper(), props);
    }

    @Test
    void nullKeyPassesThrough() {
        ResponseEntity<?> result = idempotencyService.execute(1L, null,
                () -> ResponseEntity.ok("executed"));
        assertThat(result.getBody()).isEqualTo("executed");
    }

    @Test
    void firstCallClaimsKeyAndExecutes() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);

        ResponseEntity<?> result = idempotencyService.execute(1L, "key-1",
                () -> ResponseEntity.ok("first"));

        assertThat(result.getBody()).isEqualTo("first");
        verify(valueOperations).setIfAbsent(eq("idem:1:key-1"), eq("pending"), any(Duration.class));
    }

    @Test
    void secondCallReturnsCached() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        String cachedJson = "{\"status\":200,\"body\":\"\\\"cached\\\"\"}";
        when(valueOperations.get("idem:1:key-2")).thenReturn(cachedJson);

        ResponseEntity<?> result = idempotencyService.execute(1L, "key-2",
                () -> ResponseEntity.ok("should not run"));

        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody()).isEqualTo("cached");
    }

    @Test
    void pendingKeyThrowsConflict() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(false);
        when(valueOperations.get("idem:1:key-3")).thenReturn("pending");

        assertThatThrownBy(() -> idempotencyService.execute(1L, "key-3",
                () -> ResponseEntity.ok("never")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void failedExecutionDeletesKey() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenReturn(true);

        assertThatThrownBy(() -> idempotencyService.execute(1L, "key-4",
                () -> { throw new RuntimeException("boom"); }))
                .isInstanceOf(RuntimeException.class);

        verify(redisTemplate).delete("idem:1:key-4");
    }
}
