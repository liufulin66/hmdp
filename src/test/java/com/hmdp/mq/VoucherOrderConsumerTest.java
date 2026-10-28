package com.hmdp.mq;

import com.hmdp.service.impl.VoucherOrderPersistenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;

import static com.hmdp.utils.RedisConstants.SECKILL_CONSUMER_GROUP;
import static com.hmdp.utils.RedisConstants.SECKILL_STREAM_KEY;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class VoucherOrderConsumerTest {
    private StreamOperations<String, String, String> streamOps;
    private VoucherOrderPersistenceService persistenceService;
    private VoucherOrderConsumer consumer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        streamOps = mock(StreamOperations.class);
        persistenceService = mock(VoucherOrderPersistenceService.class);
        when(redis.<String, String>opsForStream()).thenReturn(streamOps);
        consumer = new VoucherOrderConsumer(redis, persistenceService);
    }

    @Test
    void successfulPersistenceAcknowledgesMessage() {
        consumer.process(record("1-0"));

        verify(persistenceService).persistVoucherOrder(100L, 1L, 2L);
        verify(streamOps).acknowledge(SECKILL_STREAM_KEY, SECKILL_CONSUMER_GROUP, "1-0");
    }

    @Test
    void duplicateOrderIsAcknowledgedAsIdempotentSuccess() {
        doThrow(new DuplicateKeyException("duplicate"))
                .when(persistenceService).persistVoucherOrder(100L, 1L, 2L);

        consumer.process(record("1-0"));

        verify(streamOps).acknowledge(SECKILL_STREAM_KEY, SECKILL_CONSUMER_GROUP, "1-0");
        verify(streamOps, never()).add(any(MapRecord.class));
    }

    @Test
    void failureKeepsMessagePendingForRetry() {
        doThrow(new IllegalStateException("database down"))
                .when(persistenceService).persistVoucherOrder(100L, 1L, 2L);

        consumer.process(record("1-0"));

        verify(streamOps, never()).acknowledge(anyString(), anyString(), anyString());
        verify(streamOps, never()).add(any(MapRecord.class));
    }

    private MapRecord<String, String, String> record(String recordId) {
        return MapRecord.create(SECKILL_STREAM_KEY, Map.of(
                "userId", "1",
                "voucherId", "2",
                "id", "100")).withId(RecordId.of(recordId));
    }
}
