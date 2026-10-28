package com.hmdp.mq;

import com.hmdp.service.impl.VoucherOrderPersistenceService;
import com.hmdp.service.impl.VoucherOrderCompensationService;
import com.hmdp.entity.VoucherOrderTask;
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
    private VoucherOrderCompensationService compensation;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        streamOps = mock(StreamOperations.class);
        persistenceService = mock(VoucherOrderPersistenceService.class);
        when(redis.<String, String>opsForStream()).thenReturn(streamOps);
        compensation = mock(VoucherOrderCompensationService.class);
        consumer = new VoucherOrderConsumer(redis, persistenceService, compensation);
        when(persistenceService.persistVoucherOrder(100L, 1L, 2L)).thenReturn(task(VoucherOrderTask.SUCCESS));
    }

    @Test
    void successfulPersistenceAcknowledgesMessage() {
        consumer.process(record("1-0"));

        verify(persistenceService).persistVoucherOrder(100L, 1L, 2L);
        verify(streamOps).acknowledge(SECKILL_STREAM_KEY, SECKILL_CONSUMER_GROUP, "1-0");
    }

    @Test
    void duplicateOrderIsAcknowledgedAsIdempotentSuccess() {
        when(persistenceService.persistVoucherOrder(100L, 1L, 2L))
                .thenThrow(new DuplicateKeyException("duplicate"));
        when(persistenceService.recordFailure(100L, 1L, 2L)).thenReturn(task(VoucherOrderTask.SUCCESS));

        consumer.process(record("1-0"));

        verify(streamOps).acknowledge(SECKILL_STREAM_KEY, SECKILL_CONSUMER_GROUP, "1-0");
        verify(streamOps, never()).add(any(MapRecord.class));
    }

    @Test
    void failureKeepsMessagePendingForRetry() {
        when(persistenceService.persistVoucherOrder(100L, 1L, 2L))
                .thenThrow(new IllegalStateException("database down"));
        when(persistenceService.recordFailure(100L, 1L, 2L)).thenThrow(new IllegalStateException("database down"));

        consumer.process(record("1-0"));

        verify(streamOps, never()).acknowledge(anyString(), anyString(), anyString());
        verify(streamOps, never()).add(any(MapRecord.class));
        verifyNoInteractions(compensation);
    }

    @Test
    void retryableFailureKeepsMessagePending() {
        when(persistenceService.persistVoucherOrder(100L, 1L, 2L)).thenReturn(task(VoucherOrderTask.PROCESSING));
        consumer.process(record("1-0"));
        verify(streamOps, never()).acknowledge(anyString(), anyString(), anyString());
        verifyNoInteractions(compensation);
    }

    @Test
    void compensatesBeforeDeadLetterAndAcknowledgement() {
        VoucherOrderTask task = task(VoucherOrderTask.COMPENSATING);
        when(persistenceService.persistVoucherOrder(100L, 1L, 2L)).thenReturn(task);
        consumer.process(record("1-0"));
        var ordered = inOrder(compensation, streamOps);
        ordered.verify(compensation).compensate(task);
        ordered.verify(streamOps).add(any(MapRecord.class));
        ordered.verify(streamOps).acknowledge(SECKILL_STREAM_KEY, SECKILL_CONSUMER_GROUP, "1-0");
    }

    @Test
    void compensationFailureKeepsMessagePending() {
        when(persistenceService.persistVoucherOrder(100L, 1L, 2L)).thenReturn(task(VoucherOrderTask.COMPENSATING));
        doThrow(new IllegalStateException("redis down")).when(compensation).compensate(any());
        consumer.process(record("1-0"));
        verify(streamOps, never()).acknowledge(anyString(), anyString(), anyString());
        verify(streamOps, never()).add(any(MapRecord.class));
    }

    @Test
    void deadLetterFailureKeepsCompletedFailurePendingWithoutAnotherRefund() {
        when(persistenceService.persistVoucherOrder(100L, 1L, 2L)).thenReturn(task(VoucherOrderTask.FAILED));
        when(streamOps.add(any(MapRecord.class))).thenThrow(new IllegalStateException("redis down"));
        consumer.process(record("1-0"));
        verifyNoInteractions(compensation);
        verify(streamOps, never()).acknowledge(anyString(), anyString(), anyString());
    }

    private VoucherOrderTask task(String status) {
        VoucherOrderTask task = new VoucherOrderTask();
        task.setStatus(status);
        task.setOrderId(100L);
        task.setUserId(1L);
        task.setVoucherId(2L);
        return task;
    }

    private MapRecord<String, String, String> record(String recordId) {
        return MapRecord.create(SECKILL_STREAM_KEY, Map.of(
                "userId", "1",
                "voucherId", "2",
                "id", "100")).withId(RecordId.of(recordId));
    }
}
