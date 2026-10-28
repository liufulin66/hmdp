package com.hmdp.utils;

import cn.hutool.core.lang.TypeReference;
import com.hmdp.config.WebExceptionAdvice;
import com.hmdp.entity.Shop;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CacheClientTest {
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private Map<String, String> stored;
    private CacheClient client;
    private CachePolicy policy;
    private RedissonClient redisson;
    private Map<String, RLock> locks;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        stored = new ConcurrentHashMap<>();
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(call -> stored.get(call.getArgument(0)));
        doAnswer(call -> {
            stored.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(values).set(anyString(), anyString(), anyLong(), eq(TimeUnit.MILLISECONDS));
        when(redis.delete(anyString())).thenAnswer(call -> stored.remove(call.getArgument(0)) != null);
        redisson = mock(RedissonClient.class);
        locks = new ConcurrentHashMap<>();
        when(redisson.getLock(anyString())).thenAnswer(call -> locks.computeIfAbsent(call.getArgument(0), key -> {
            RLock lock = mock(RLock.class);
            when(lock.tryLock()).thenReturn(true);
            return lock;
        }));
        client = new CacheClient(redis, redisson);
        policy = policy("test", 1);
    }

    @Test
    void supportsLongIntegerStringAndUuidKeys() {
        assertIdRoundTrip("long:", 7L);
        assertIdRoundTrip("integer:", 7);
        assertIdRoundTrip("string:", "shop-A");
        assertIdRoundTrip("uuid:", UUID.fromString("f5a2d6b2-564c-4ac7-b098-6111f428af11"));
    }

    @Test
    void stringsIncludingEmptyAndJsonLookingTextRemainDistinctFromNull() {
        assertScalarRoundTrip("text", "普通文本\n带引号\"和反斜线\\", String.class);
        assertScalarRoundTrip("empty", "", String.class);
        assertScalarRoundTrip("json-text", "{\"id\":1}", String.class);
        assertEquals("\"\"", stored.get("empty"));
    }

    @Test
    void supportsNumbersBooleansAndArrays() {
        assertScalarRoundTrip("long", Long.MAX_VALUE, Long.class);
        assertScalarRoundTrip("integer", 42, Integer.class);
        assertScalarRoundTrip("boolean", false, Boolean.class);
        int[] expected = {1, 2, 3};
        client.queryWithMutex("array", "lock:array", int[].class, () -> expected, policy);
        assertArrayEquals(expected, client.queryWithMutex("array", "lock:array", int[].class,
                () -> fail("数组命中后不应回源"), policy));
    }

    @Test
    void preservesEntityAndDateFields() {
        Shop expected = shop().setCreateTime(LocalDateTime.of(2026, 10, 24, 12, 30));
        assertScalarRoundTrip("entity", expected, Shop.class);
    }

    @Test
    void restoresListElementTypesThroughIdOverload() {
        TypeReference<List<Shop>> type = new TypeReference<>() { };
        List<Shop> expected = List.of(shop());
        client.queryWithMutex("types:", "lock:types:", "food", type, id -> expected, policy);
        List<Shop> cached = client.queryWithMutex("types:", "lock:types:", "food", type,
                id -> fail("集合命中后不应回源"), policy);
        assertEquals(expected, cached);
        assertInstanceOf(Shop.class, cached.get(0));
    }

    @Test
    void restoresNestedMapAndListTypes() {
        TypeReference<Map<String, List<Shop>>> type = new TypeReference<>() { };
        Map<String, List<Shop>> expected = Map.of("food", List.of(shop()));
        client.queryWithMutex("nested", "lock:nested", type, () -> expected, policy);
        Map<String, List<Shop>> cached = client.queryWithMutex("nested", "lock:nested", type,
                () -> fail("嵌套泛型命中后不应回源"), policy);
        assertEquals(expected, cached);
        assertInstanceOf(Shop.class, cached.get("food").get(0));
    }

    @Test
    void restoresGenericPageDtoElementTypes() {
        TypeReference<PageResult<Shop>> type = new TypeReference<>() { };
        PageResult<Shop> expected = new PageResult<>(List.of(shop()), 1L);
        client.queryWithMutex("page", "lock:page", type, () -> expected, policy);
        PageResult<Shop> cached = client.queryWithMutex("page", "lock:page", type,
                () -> fail("分页 DTO 命中后不应回源"), policy);
        assertEquals(expected, cached);
        assertInstanceOf(Shop.class, cached.getRecords().get(0));
    }

    @Test
    void supplierSupportsQueriesWithMultipleParametersAndIsolatesKeys() {
        Search query = new Search("上海", 2, 1);
        String key = "shops:city=上海:type=2:page=1";
        AtomicInteger calls = new AtomicInteger();
        TypeReference<List<Shop>> type = new TypeReference<>() { };
        Supplier<List<Shop>> loader = () -> {
            calls.incrementAndGet();
            return List.of(shop().setArea(query.city()).setTypeId((long) query.typeId()));
        };
        client.queryWithMutex(key, "lock:" + key, type, loader, policy);
        List<Shop> cached = client.queryWithMutex(key, "lock:" + key, type, loader, policy);
        assertEquals("上海", cached.get(0).getArea());
        assertEquals(1, calls.get());
        List<Shop> anotherPage = client.queryWithMutex("shops:city=上海:type=2:page=2", "lock:page2",
                type, List::of, policy);
        assertTrue(anotherPage.isEmpty());
    }

    @Test
    void nullIsANegativeHitAndUsesShortTtl() {
        AtomicInteger calls = new AtomicInteger();
        Supplier<Shop> loader = () -> { calls.incrementAndGet(); return null; };
        assertNull(client.queryWithMutex("missing", "lock:missing", Shop.class, loader, policy));
        assertNull(client.queryWithMutex("missing", "lock:missing", Shop.class, loader, policy));
        assertEquals(1, calls.get());
        assertEquals("", stored.get("missing"));
        verify(values).set("missing", "", policy.emptyTtl().toMillis(), TimeUnit.MILLISECONDS);
    }

    @Test
    void emptyCollectionUsesConfiguredShortTtlWithoutBecomingNull() {
        TypeReference<List<Shop>> type = new TypeReference<>() { };
        assertEquals(List.of(), client.queryWithMutex("empty-list", "lock:empty-list",
                type, List::of, policy, List::isEmpty));
        assertEquals(List.of(), client.queryWithMutex("empty-list", "lock:empty-list",
                type, () -> fail("空集合是命中"), policy, List::isEmpty));
        verify(values).set("empty-list", "[]", policy.emptyTtl().toMillis(), TimeUnit.MILLISECONDS);
    }

    @Test
    void businessCanChooseToCacheEmptyCollectionWithNormalTtl() {
        client.queryWithMutex("list", "lock:list", new TypeReference<List<Shop>>() { }, List::of, policy);
        verify(values).set("list", "[]", policy.ttl().toMillis(), TimeUnit.MILLISECONDS);
    }

    @Test
    void corruptedCacheIsRebuilt() {
        stored.put("broken", "{broken");
        assertEquals(shop(), client.queryWithMutex("broken", "lock:broken", Shop.class,
                CacheClientTest::shop, policy));
        assertEquals(shop(), client.queryWithMutex("broken", "lock:broken", Shop.class,
                () -> fail("修复后应命中"), policy));
    }

    @Test
    void databaseDataAccessExceptionPropagatesOnceWithoutCachingAbsenceAndReleasesPermit() {
        AtomicInteger calls = new AtomicInteger();
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("database failed");
        assertSame(failure, assertThrows(DataAccessResourceFailureException.class,
                () -> client.queryWithMutex("db", "lock:db", Shop.class, () -> {
                    calls.incrementAndGet();
                    throw failure;
                }, policy)));
        assertEquals(1, calls.get());
        assertFalse(stored.containsKey("db"));
        verify(locks.get("lock:db")).unlock();
        assertEquals(shop(), client.queryWithMutex("db", "lock:db", Shop.class, CacheClientTest::shop, policy));
    }

    @Test
    void loadLimitsAreSharedWithinBusinessAndIsolatedBetweenBusinesses() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> client.queryWithMutex("first", "lock:first", Shop.class, () -> {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test timed out");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return shop();
            }, policy));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertThrows(CacheBusyException.class, () -> client.queryWithMutex("second", "lock:second",
                    Shop.class, CacheClientTest::shop, policy));
            assertEquals(shop(), client.queryWithMutex("other", "lock:other", Shop.class,
                    CacheClientTest::shop, policy("other-business", 1)));
            release.countDown();
            assertEquals(shop(), first.get(3, TimeUnit.SECONDS));
            assertEquals(shop(), client.queryWithMutex("second", "lock:second", Shop.class,
                    CacheClientTest::shop, policy));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsInconsistentLimitsForSameBusiness() {
        client.queryWithMutex("first", "lock:first", Shop.class, CacheClientTest::shop, policy);
        assertThrows(IllegalArgumentException.class, () -> client.queryWithMutex("other", "lock:other",
                Shop.class, CacheClientTest::shop, policy("test", 2)));
    }

    @Test
    void interruptedLockWaitPreservesInterruptFlagAndDoesNotReportNotFound() {
        RLock busyLock = redisson.getLock("lock:busy");
        when(busyLock.tryLock()).thenReturn(false);
        Thread.currentThread().interrupt();
        try {
            assertThrows(CacheBusyException.class, () -> client.queryWithMutex("busy", "lock:busy",
                    Shop.class, () -> fail("未拿到锁不能回源"), policy));
            assertTrue(Thread.currentThread().isInterrupted());
            verify(busyLock, never()).unlock();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void invalidationOutsideTransactionDeletesImmediately() {
        stored.put("value", "old");
        client.evictAfterCommit("value", policy);
        assertFalse(stored.containsKey("value"));
    }

    @Test
    void rejectsInvalidPoliciesAndConflictingKeysBeforeLoading() {
        assertThrows(IllegalArgumentException.class, () -> new CachePolicy("test", Duration.ZERO,
                policy.emptyTtl(), policy.jitter(), 1, Duration.ZERO, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new CachePolicy("test", policy.ttl(),
                policy.emptyTtl(), Duration.ofSeconds(-1), 1, Duration.ZERO, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> client.queryWithMutex("same", "same", Shop.class,
                () -> fail("无效参数不能回源"), policy));
        verifyNoInteractions(redis);
    }

    @Test
    void busyExceptionHasDedicatedResponse() {
        var result = new WebExceptionAdvice().handleCacheBusyException(new CacheBusyException());
        assertFalse(result.getSuccess());
        assertEquals("查询繁忙，请稍后重试", result.getErrorMsg());
    }

    private <ID> void assertIdRoundTrip(String prefix, ID id) {
        AtomicInteger calls = new AtomicInteger();
        Shop expected = client.queryWithMutex(prefix, "lock:" + prefix, id, Shop.class, actualId -> {
            assertEquals(id, actualId);
            calls.incrementAndGet();
            return shop();
        }, policy);
        Shop cached = client.queryWithMutex(prefix, "lock:" + prefix, id, Shop.class,
                actualId -> fail("命中后不应回源"), policy);
        assertEquals(expected, cached);
        assertEquals(1, calls.get());
        assertTrue(stored.containsKey(prefix + id));
    }

    private <R> void assertScalarRoundTrip(String key, R expected, Class<R> type) {
        assertEquals(expected, client.queryWithMutex(key, "lock:" + key, type, () -> expected, policy));
        assertEquals(expected, client.queryWithMutex(key, "lock:" + key, type,
                () -> fail("命中后不应回源"), policy));
    }

    private static Shop shop() {
        return new Shop().setId(1L).setName("测试商户");
    }

    private static CachePolicy policy(String group, int concurrency) {
        return new CachePolicy(group, Duration.ofMinutes(30), Duration.ofMinutes(2), Duration.ZERO,
                3, Duration.ZERO, concurrency, 3);
    }

    private record Search(String city, int typeId, int page) { }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PageResult<T> {
        private List<T> records;
        private long total;
    }
}
