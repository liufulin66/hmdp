package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.CacheBusyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShopServiceImplTest {
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private ShopServiceImpl service;
    private Shop shop;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        service = spy(new ShopServiceImpl());
        ReflectionTestUtils.setField(service, "cacheClient", new CacheClient(redis));
        shop = new Shop().setId(1L).setName("测试商户");
    }

    @Test
    void rejectsInvalidIdsWithoutAccessingStorage() {
        assertFalse(service.queryShopById(null).getSuccess());
        assertFalse(service.queryShopById(0L).getSuccess());
        assertFalse(service.queryShopById(-1L).getSuccess());
        verifyNoInteractions(redis);
        verify(service, never()).getById(any());
    }

    @Test
    void cacheHitDoesNotLoadDatabaseOrExtendTtl() {
        when(values.get(CACHE_SHOP_KEY + 1)).thenReturn(JSONUtil.toJsonStr(shop));
        Result result = service.queryShopById(1L);
        assertTrue(result.getSuccess());
        assertEquals(shop.getName(), ((Shop) result.getData()).getName());
        verify(service, never()).getById(any());
        verify(values, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        verify(redis, never()).expire(anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void cachedAbsenceDoesNotLoadDatabase() {
        when(values.get(CACHE_SHOP_KEY + 1)).thenReturn("");
        assertEquals("商户不存在", service.queryShopById(1L).getErrorMsg());
        verify(service, never()).getById(any());
    }

    @Test
    void cacheMissLoadsAndCachesWithJitterAndReleasesOwnLock() {
        acquireLock();
        doReturn(shop).when(service).getById(1L);
        assertTrue(service.queryShopById(1L).getSuccess());
        var ttl = org.mockito.ArgumentCaptor.forClass(Long.class);
        verify(values).set(eq(CACHE_SHOP_KEY + 1), eq(JSONUtil.toJsonStr(shop)),
                ttl.capture(), eq(TimeUnit.MILLISECONDS));
        assertTrue(ttl.getValue() >= TimeUnit.MINUTES.toMillis(CACHE_SHOP_TTL));
        assertTrue(ttl.getValue() <= TimeUnit.MINUTES.toMillis(CACHE_SHOP_TTL)
                + TimeUnit.SECONDS.toMillis(CACHE_SHOP_JITTER_SECONDS));
        var owner = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(values).setIfAbsent(eq(LOCK_SHOP_KEY + 1), owner.capture(),
                eq(TimeUnit.SECONDS.toMillis(LOCK_SHOP_TTL)), eq(TimeUnit.MILLISECONDS));
        verify(redis).execute(any(RedisScript.class), eq(List.of(LOCK_SHOP_KEY + 1)), eq(owner.getValue()));
    }

    @Test
    void missingShopGetsShortLivedNegativeCache() {
        acquireLock();
        doReturn(null).when(service).getById(1L);
        assertFalse(service.queryShopById(1L).getSuccess());
        verify(values).set(CACHE_SHOP_KEY + 1, "", TimeUnit.MINUTES.toMillis(CACHE_NULL_TTL), TimeUnit.MILLISECONDS);
    }

    @Test
    void rechecksCacheAfterAcquiringLock() {
        acquireLock();
        when(values.get(CACHE_SHOP_KEY + 1)).thenReturn(null, JSONUtil.toJsonStr(shop));
        assertTrue(service.queryShopById(1L).getSuccess());
        verify(service, never()).getById(any());
        verify(redis).execute(any(RedisScript.class), eq(List.of(LOCK_SHOP_KEY + 1)), anyString());
    }

    @Test
    void waitingRequestUsesCacheFilledByAnotherRequest() {
        when(values.get(CACHE_SHOP_KEY + 1)).thenReturn(null, JSONUtil.toJsonStr(shop));
        when(values.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(false);
        assertTrue(service.queryShopById(1L).getSuccess());
        verify(service, never()).getById(any());
        verify(redis, never()).execute(any(RedisScript.class), anyList(), anyString());
    }

    @Test
    void lockContentionHasBoundedRetriesAndDoesNotLoadDatabase() {
        when(values.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(false);
        assertThrows(CacheBusyException.class, () -> service.queryShopById(1L));
        verify(values, times(SHOP_LOCK_MAX_ATTEMPTS)).setIfAbsent(
                anyString(), anyString(), anyLong(), any(TimeUnit.class));
        verify(service, never()).getById(any());
    }

    @Test
    void redisReadFailureFallsBackWithoutWritingCache() {
        when(values.get(anyString())).thenThrow(redisFailure());
        doReturn(shop).when(service).getById(1L);
        assertTrue(service.queryShopById(1L).getSuccess());
        verify(values, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void cacheWriteAndUnlockFailuresDoNotDiscardDatabaseResult() {
        acquireLock();
        doReturn(shop).when(service).getById(1L);
        doThrow(redisFailure()).when(values).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        when(redis.execute(any(RedisScript.class), anyList(), anyString())).thenThrow(redisFailure());
        assertSame(shop, service.queryShopById(1L).getData());
    }

    @Test
    void databaseExceptionStillReleasesLockAndPermit() {
        acquireLock();
        doThrow(new IllegalStateException("database unavailable")).doReturn(shop).when(service).getById(1L);
        assertThrows(IllegalStateException.class, () -> service.queryShopById(1L));
        assertTrue(service.queryShopById(1L).getSuccess());
        verify(redis, times(2)).execute(any(RedisScript.class), anyList(), anyString());
    }

    @Test
    void redisOutageDoesNotAllowUnlimitedDatabaseConcurrency() throws Exception {
        when(values.get(anyString())).thenThrow(redisFailure());
        CountDownLatch entered = new CountDownLatch(SHOP_MAX_CONCURRENT_LOADS);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test timed out");
            }
            return shop;
        }).when(service).getById(1L);
        ExecutorService executor = Executors.newFixedThreadPool(SHOP_MAX_CONCURRENT_LOADS);
        List<Future<Result>> requests = new ArrayList<>();
        try {
            for (int i = 0; i < SHOP_MAX_CONCURRENT_LOADS; i++) {
                requests.add(executor.submit(() -> service.queryShopById(1L)));
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertThrows(CacheBusyException.class, () -> service.queryShopById(1L));
            verify(service, times(SHOP_MAX_CONCURRENT_LOADS)).getById(1L);
            release.countDown();
            for (Future<Result> request : requests) {
                assertTrue(request.get(5, TimeUnit.SECONDS).getSuccess());
            }
            assertTrue(service.queryShopById(1L).getSuccess());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void updateInvalidatesOnlyAfterTransactionCommit() {
        TestTransactionManager manager = new TestTransactionManager();
        doReturn(true).when(service).updateById(shop);
        when(redis.delete(CACHE_SHOP_KEY + 1)).thenAnswer(invocation -> {
            assertTrue(manager.committed);
            return true;
        });
        assertTrue(transactionalService(manager).updateShop(shop).getSuccess());
        verify(redis).delete(CACHE_SHOP_KEY + 1);
    }

    @Test
    void rolledBackUpdateDoesNotInvalidateCache() {
        doReturn(true).when(service).updateById(shop);
        new TransactionTemplate(new TestTransactionManager()).execute(status -> {
            assertTrue(service.updateShop(shop).getSuccess());
            verify(redis, never()).delete(anyString());
            status.setRollbackOnly();
            return null;
        });
        verify(redis, never()).delete(anyString());
    }

    @Test
    void failedUpdateDoesNotInvalidateCache() {
        doReturn(false).when(service).updateById(shop);
        assertFalse(transactionalService(new TestTransactionManager()).updateShop(shop).getSuccess());
        verify(redis, never()).delete(anyString());
    }

    @Test
    void deleteFailureRetriesAndStopsOnSuccess() {
        doReturn(true).when(service).updateById(shop);
        when(redis.delete(CACHE_SHOP_KEY + 1)).thenThrow(redisFailure()).thenReturn(true);
        assertTrue(transactionalService(new TestTransactionManager()).updateShop(shop).getSuccess());
        verify(redis, times(2)).delete(CACHE_SHOP_KEY + 1);
    }

    @Test
    void exhaustedDeleteRetriesDoNotReportCommittedUpdateAsFailed() {
        doReturn(true).when(service).updateById(shop);
        when(redis.delete(CACHE_SHOP_KEY + 1)).thenThrow(redisFailure());
        assertTrue(transactionalService(new TestTransactionManager()).updateShop(shop).getSuccess());
        verify(redis, times(SHOP_CACHE_DELETE_MAX_ATTEMPTS)).delete(CACHE_SHOP_KEY + 1);
    }

    @Test
    void creatingShopInvalidatesPriorNegativeCacheAfterCommit() {
        doReturn(true).when(service).save(shop);
        Result result = transactionalService(new TestTransactionManager()).saveShop(shop);
        assertTrue(result.getSuccess());
        assertEquals(1L, result.getData());
        verify(redis).delete(CACHE_SHOP_KEY + 1);
    }

    private void acquireLock() {
        when(values.setIfAbsent(anyString(), anyString(), anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(true);
    }

    private DataAccessResourceFailureException redisFailure() {
        return new DataAccessResourceFailureException("simulated Redis failure");
    }

    private IShopService transactionalService(TestTransactionManager manager) {
        TransactionInterceptor interceptor = new TransactionInterceptor();
        interceptor.setTransactionManager(manager);
        interceptor.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory factory = new ProxyFactory(service);
        factory.addAdvice(interceptor);
        return (IShopService) factory.getProxy();
    }

    private static class TestTransactionManager extends AbstractPlatformTransactionManager {
        private boolean committed;

        @Override
        protected Object doGetTransaction() { return new Object(); }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) { }

        @Override
        protected void doCommit(DefaultTransactionStatus status) { committed = true; }

        @Override
        protected void doRollback(DefaultTransactionStatus status) { }
    }
}
