package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_TTL;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShopTypeServiceImplTest {
    private ValueOperations<String, String> values;
    private ShopTypeServiceImpl service;
    private List<ShopType> types;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        service = spy(new ShopTypeServiceImpl());
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        types = List.of(
                new ShopType().setId(2L).setName("美食").setIcon("food.png").setSort(1),
                new ShopType().setId(1L).setName("酒店").setIcon("hotel.png").setSort(2));
    }

    @Test
    void cacheHitPreservesListOrderWithoutLoadingDatabaseOrExtendingTtl() {
        when(values.get(CACHE_SHOP_TYPE_KEY)).thenReturn(JSONUtil.toJsonStr(types));

        Result result = service.queryTypeList();

        assertTrue(result.getSuccess());
        assertEquals(types, result.getData());
        verify(service, never()).list(any(Wrapper.class));
        verify(values, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void cachedEmptyArrayIsAHit() {
        when(values.get(CACHE_SHOP_TYPE_KEY)).thenReturn("[]");

        Result result = service.queryTypeList();

        assertTrue(result.getSuccess());
        assertEquals(List.of(), result.getData());
        verify(service, never()).list(any(Wrapper.class));
        verify(values, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void cacheMissQueriesInSortOrderAndCachesListForThirtyMinutes() {
        doReturn(types).when(service).list(any(Wrapper.class));

        Result result = service.queryTypeList();

        assertTrue(result.getSuccess());
        assertSame(types, result.getData());
        verify(service).list(argThat((Wrapper<ShopType> query) ->
                "ORDER BY sort ASC".equals(query.getSqlSegment().trim())));
        verify(values).set(CACHE_SHOP_TYPE_KEY, JSONUtil.toJsonStr(types),
                CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);
    }

    @Test
    void emptyDatabaseResultIsCachedForTwoMinutes() {
        doReturn(List.of()).when(service).list(any(Wrapper.class));

        Result result = service.queryTypeList();

        assertTrue(result.getSuccess());
        assertEquals(List.of(), result.getData());
        verify(values).set(CACHE_SHOP_TYPE_KEY, "[]", CACHE_NULL_TTL, TimeUnit.MINUTES);
    }

    @Test
    void redisReadFailureFallsBackWithoutTryingToWriteCache() {
        when(values.get(CACHE_SHOP_TYPE_KEY)).thenThrow(redisFailure());
        doReturn(types).when(service).list(any(Wrapper.class));

        Result result = service.queryTypeList();

        assertTrue(result.getSuccess());
        assertSame(types, result.getData());
        verify(values, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void redisWriteFailureStillReturnsDatabaseResult() {
        doReturn(types).when(service).list(any(Wrapper.class));
        doThrow(redisFailure()).when(values).set(
                anyString(), anyString(), anyLong(), any(TimeUnit.class));

        Result result = service.queryTypeList();

        assertTrue(result.getSuccess());
        assertSame(types, result.getData());
    }

    @Test
    void databaseFailureDoesNotCacheAnEmptyList() {
        doThrow(new DataAccessResourceFailureException("database unavailable"))
                .when(service).list(any(Wrapper.class));

        assertThrows(DataAccessResourceFailureException.class, service::queryTypeList);

        verify(values, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    private DataAccessResourceFailureException redisFailure() {
        return new DataAccessResourceFailureException("simulated Redis failure");
    }
}
