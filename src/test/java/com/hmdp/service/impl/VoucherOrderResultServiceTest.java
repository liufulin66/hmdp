package com.hmdp.service.impl;

import com.hmdp.dto.UserDTO;
import com.hmdp.dto.VoucherOrderResultDTO;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.entity.VoucherOrderTask;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.mapper.VoucherOrderTaskMapper;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static com.hmdp.utils.RedisConstants.SECKILL_RESULT_KEY;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VoucherOrderResultServiceTest {
    private VoucherOrderResultService service;
    private VoucherOrderMapper orders;
    private VoucherOrderTaskMapper tasks;
    private StringRedisTemplate redis;
    private HashOperations<String, Object, Object> hash;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        orders = mock(VoucherOrderMapper.class);
        tasks = mock(VoucherOrderTaskMapper.class);
        redis = mock(StringRedisTemplate.class);
        hash = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hash);
        service = new VoucherOrderResultService();
        ReflectionTestUtils.setField(service, "orderMapper", orders);
        ReflectionTestUtils.setField(service, "taskMapper", tasks);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        UserDTO user = new UserDTO();
        user.setId(1L);
        UserHolder.saveUser(user);
    }

    @AfterEach
    void tearDown() { UserHolder.removeUser(); }

    @Test
    void queuedReservationIsProcessingAndIdentifiersStayStrings() {
        when(hash.entries(SECKILL_RESULT_KEY + 100)).thenReturn(Map.of(
                "orderId", "100", "userId", "1", "voucherId", "2"));
        var dto = (VoucherOrderResultDTO) service.queryResult(100L).getData();
        assertEquals("PROCESSING", dto.status());
        assertEquals("100", dto.orderId());
        assertEquals("2", dto.voucherId());
    }

    @Test
    void successfulLegacyOrderNeedsNoRedisRecord() {
        VoucherOrder order = new VoucherOrder();
        order.setId(100L); order.setUserId(1L); order.setVoucherId(2L); order.setStatus(2);
        when(orders.selectById(100L)).thenReturn(order);
        var dto = (VoucherOrderResultDTO) service.queryResult(100L).getData();
        assertEquals("SUCCESS", dto.status());
        assertEquals(2, dto.orderStatus());
        verifyNoInteractions(redis);
    }

    @Test
    void cannotReadAnotherUsersOrderOrQueuedReservation() {
        VoucherOrder order = new VoucherOrder(); order.setUserId(9L);
        when(orders.selectById(100L)).thenReturn(order);
        assertEquals("下单记录不存在", service.queryResult(100L).getErrorMsg());
        when(orders.selectById(100L)).thenReturn(null);
        when(hash.entries(SECKILL_RESULT_KEY + 100)).thenReturn(Map.of(
                "orderId", "100", "userId", "9", "voucherId", "2"));
        assertEquals("下单记录不存在", service.queryResult(100L).getErrorMsg());
    }

    @Test
    void databaseFailureIsVisibleWithoutRedisAndCompensationIsNotReportedEarly() {
        VoucherOrderTask task = new VoucherOrderTask();
        task.setOrderId(100L); task.setUserId(1L); task.setVoucherId(2L);
        task.setStatus(VoucherOrderTask.COMPENSATING);
        when(tasks.selectById(100L)).thenReturn(task);
        var pending = (VoucherOrderResultDTO) service.queryResult(100L).getData();
        assertEquals("PROCESSING", pending.status());
        assertFalse(pending.compensated());
        task.setStatus(VoucherOrderTask.FAILED); task.setFailureReason("已恢复");
        var failed = (VoucherOrderResultDTO) service.queryResult(100L).getData();
        assertEquals("FAILED", failed.status());
        assertTrue(failed.compensated());
        verifyNoInteractions(redis);
    }

    @Test
    void validatesIdAndRequiresLogin() {
        assertFalse(service.queryResult(0L).getSuccess());
        UserHolder.removeUser();
        assertEquals("请先登录", service.queryResult(100L).getErrorMsg());
        verifyNoInteractions(orders, tasks, redis);
    }
}
