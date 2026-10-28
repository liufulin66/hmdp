package com.hmdp.service.impl;

import com.hmdp.entity.VoucherOrderTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VoucherOrderCompensationServiceTest {
    private VoucherOrderCompensationService service;
    private VoucherOrderPersistenceService persistence;
    private StringRedisTemplate redis;
    private VoucherOrderTask task;

    @BeforeEach
    void setUp() {
        service = new VoucherOrderCompensationService();
        persistence = mock(VoucherOrderPersistenceService.class);
        redis = mock(StringRedisTemplate.class);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(service, "persistenceService", persistence);
        ReflectionTestUtils.setField(service, "compensateSeckillScript", new DefaultRedisScript<>("return 1", Long.class));
        task = new VoucherOrderTask();
        task.setOrderId(100L); task.setUserId(1L); task.setVoucherId(2L);
        task.setStatus(VoucherOrderTask.COMPENSATING); task.setRestoreQualification(true);
    }

    @Test
    void completesDatabaseStateOnlyAfterRedisConfirmsCompensation() {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any())).thenReturn(1L);
        service.compensate(task);
        var ordered = inOrder(redis, persistence);
        ordered.verify(redis).execute(any(RedisScript.class), anyList(), eq("1"), eq("100"), eq("1"));
        ordered.verify(persistence).completeCompensation(100L);
    }

    @Test
    void missingReservationDoesNotMarkCompensationComplete() {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any())).thenReturn(-1L);
        assertThrows(IllegalStateException.class, () -> service.compensate(task));
        verifyNoInteractions(persistence);
    }

    @Test
    void cannotRefundSuccessfulTask() {
        task.setStatus(VoucherOrderTask.SUCCESS);
        assertThrows(IllegalArgumentException.class, () -> service.compensate(task));
        verifyNoInteractions(redis, persistence);
    }
}
