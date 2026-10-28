package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.utils.GlobalIdGenerator;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Map;

import static com.hmdp.utils.RedisConstants.SECKILL_META_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VoucherOrderServiceImplTest {
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private HashOperations<String, Object, Object> hash;
    private ISeckillVoucherService seckillVoucherService;
    private VoucherOrderServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        hash = mock(HashOperations.class);
        seckillVoucherService = mock(ISeckillVoucherService.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForHash()).thenReturn(hash);
        service = new VoucherOrderServiceImpl();
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(service, "seckillVoucherService", seckillVoucherService);
        ReflectionTestUtils.setField(service, "globalIdGenerator", new GlobalIdGenerator(1, 1));
        ReflectionTestUtils.setField(service, "seckillScript",
                new DefaultRedisScript<>("return 0", Long.class));
        UserDTO user = new UserDTO();
        user.setId(1L);
        UserHolder.saveUser(user);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    void rejectsInvalidVoucherIdWithoutTouchingRedis() {
        Result result = service.createVoucherOrder(0L);

        assertFalse(result.getSuccess());
        assertEquals("优惠券ID必须为正数", result.getErrorMsg());
        verifyNoInteractions(redis);
    }

    @Test
    void successReturnsOrderIdString() {
        givenReadyMeta();
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any(), any())).thenReturn(0L);

        Result result = service.createVoucherOrder(2L);

        assertTrue(result.getSuccess());
        assertTrue(Long.parseLong((String) result.getData()) > 0);
    }

    @ParameterizedTest
    @CsvSource({
            "1, 库存不足",
            "2, 不能重复下单",
            "3, 秒杀活动未就绪",
            "4, 秒杀尚未开始",
            "5, 秒杀已结束",
            "9, 秒杀失败，请稍后重试"
    })
    void mapsLuaReturnCodes(int code, String message) {
        givenReadyMeta();
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenReturn((long) code);

        Result result = service.createVoucherOrder(2L);

        assertFalse(result.getSuccess());
        assertEquals(message, result.getErrorMsg());
    }

    @Test
    void loadsMetaAndRepairsStockWhenMissing() {
        when(hash.entries(SECKILL_META_KEY + 2L)).thenReturn(Map.of());
        SeckillVoucher seckillVoucher = new SeckillVoucher()
                .setVoucherId(2L)
                .setStock(10)
                .setBeginTime(LocalDateTime.of(2026, 11, 1, 0, 0))
                .setEndTime(LocalDateTime.of(2026, 11, 30, 23, 59));
        when(seckillVoucherService.getById(2L)).thenReturn(seckillVoucher);
        when(redis.hasKey(SECKILL_STOCK_KEY + 2L)).thenReturn(false);
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any(), any())).thenReturn(0L);

        Result result = service.createVoucherOrder(2L);

        assertTrue(result.getSuccess());
        verify(hash).putAll(eq(SECKILL_META_KEY + 2L), anyMap());
        verify(values).setIfAbsent(SECKILL_STOCK_KEY + 2L, "10");
    }

    @Test
    void missingSeckillVoucherReturnsFriendlyError() {
        when(hash.entries(SECKILL_META_KEY + 2L)).thenReturn(Map.of());
        when(seckillVoucherService.getById(2L)).thenReturn(null);

        Result result = service.createVoucherOrder(2L);

        assertFalse(result.getSuccess());
        assertEquals("秒杀活动不存在", result.getErrorMsg());
        verify(redis, never()).execute(any(RedisScript.class), anyList(), any(), any(), any(), any());
    }

    @Test
    void redisFailureReturnsBusyMessage() {
        givenReadyMeta();
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("redis down"));

        Result result = service.createVoucherOrder(2L);

        assertFalse(result.getSuccess());
        assertEquals("秒杀服务繁忙，请稍后重试", result.getErrorMsg());
    }

    @Test
    void withoutLoggedInUserReturnsLoginPrompt() {
        UserHolder.removeUser();

        Result result = service.createVoucherOrder(2L);

        assertFalse(result.getSuccess());
        assertEquals("请先登录", result.getErrorMsg());
        verifyNoInteractions(redis);
    }

    private void givenReadyMeta() {
        when(hash.entries(SECKILL_META_KEY + 2L))
                .thenReturn(Map.of("beginTime", "0", "endTime", "9999999999999"));
    }
}
