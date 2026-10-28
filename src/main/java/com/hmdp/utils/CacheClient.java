package com.hmdp.utils;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.lang.TypeReference;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.lang.reflect.Type;
import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 通用旁路缓存：空值缓存、互斥重建、随机 TTL、限流回源、提交后失效。
 * 不依赖具体实体和 HTTP Result；loader 抛出的异常原样传播，不能当作空结果缓存。
 */
@Slf4j
@Component
public class CacheClient {
    private final StringRedisTemplate redis;
    private final RedissonClient redisson;
    private final ConcurrentMap<String, LoadLimiter> loadLimiters = new ConcurrentHashMap<>();

    public CacheClient(StringRedisTemplate redis, RedissonClient redisson) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.redisson = Objects.requireNonNull(redisson, "redisson");
    }

    /** 简单主键入口；ID 可以是 Long、Integer、String、UUID 等，前缀由业务隔离。 */
    public <ID, R> R queryWithMutex(String cachePrefix, String lockPrefix, ID id, Class<R> type,
                                  Function<? super ID, ? extends R> loader, CachePolicy policy) {
        Objects.requireNonNull(loader, "loader");
        return queryWithMutex(key(cachePrefix, id), key(lockPrefix, id), type, () -> loader.apply(id), policy);
    }

    /** 集合、Map、分页 DTO 等嵌套泛型请传入具体的 TypeReference。 */
    public <ID, R> R queryWithMutex(String cachePrefix, String lockPrefix, ID id, TypeReference<R> type,
                                  Function<? super ID, ? extends R> loader, CachePolicy policy) {
        Objects.requireNonNull(loader, "loader");
        return queryWithMutex(key(cachePrefix, id), key(lockPrefix, id), type, () -> loader.apply(id), policy);
    }

    /** 完整 key 入口；Supplier 可以捕获多个查询参数，key 必须包含所有影响结果的条件。 */
    public <R> R queryWithMutex(String cacheKey, String lockKey, Class<R> type,
                               Supplier<? extends R> loader, CachePolicy policy) {
        return query(cacheKey, lockKey, Objects.requireNonNull(type, "type"), loader, policy, value -> false);
    }

    public <R> R queryWithMutex(String cacheKey, String lockKey, TypeReference<R> type,
                               Supplier<? extends R> loader, CachePolicy policy) {
        return queryWithMutex(cacheKey, lockKey, type, loader, policy, value -> false);
    }

    /** shortLived 仅决定是否使用 emptyTtl，不将空集合等有效数据转换为 null。 */
    public <R> R queryWithMutex(String cacheKey, String lockKey, TypeReference<R> type,
                               Supplier<? extends R> loader, CachePolicy policy,
                               Predicate<? super R> shortLived) {
        return query(cacheKey, lockKey, Objects.requireNonNull(type, "type").getType(),
                loader, policy, shortLived);
    }

    public <R> R queryWithMutex(String cacheKey, String lockKey, Class<R> type,
                               Supplier<? extends R> loader, CachePolicy policy,
                               Predicate<? super R> shortLived) {
        return query(cacheKey, lockKey, Objects.requireNonNull(type, "type"), loader, policy, shortLived);
    }

    private <R> R query(String cacheKey, String lockKey, Type type, Supplier<? extends R> loader,
                        CachePolicy policy, Predicate<? super R> shortLived) {
        requireKey(cacheKey);
        requireKey(lockKey);
        if (cacheKey.equals(lockKey)) {
            throw new IllegalArgumentException("缓存 key 和锁 key 不能相同");
        }
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(shortLived, "shortLived");
        LoadLimiter limiter = limiter(policy);

        for (int attempt = 0; attempt < policy.maxAttempts(); attempt++) {
            boolean locked;
            RLock lock;
            try {
                CacheRead<R> cached = readCache(cacheKey, type);
                if (cached.hit()) {
                    return cached.value();
                }
                lock = redisson.getLock(lockKey);
                // 不指定 leaseTime：由看门狗续期；竞争等待仍由现有有限重试控制。
                locked = lock.tryLock();
            } catch (DataAccessException | RedisException e) {
                log.warn("缓存不可用，限流回源，key={}", cacheKey, e);
                return load(cacheKey, loader, policy, shortLived, limiter, false);
            }

            if (locked) {
                try {
                    try {
                        CacheRead<R> cached = readCache(cacheKey, type);
                        if (cached.hit()) {
                            return cached.value();
                        }
                    } catch (DataAccessException e) {
                        log.warn("缓存复查失败，限流回源，key={}", cacheKey, e);
                        return load(cacheKey, loader, policy, shortLived, limiter, false);
                    }
                    return load(cacheKey, loader, policy, shortLived, limiter, true);
                } finally {
                    unlock(lock);
                }
            }
            if (attempt + 1 < policy.maxAttempts() && !pause(policy)) {
                throw new CacheBusyException();
            }
        }
        throw new CacheBusyException();
    }

    private <R> CacheRead<R> readCache(String key, Type type) {
        String json = redis.opsForValue().get(key);
        if (json == null) {
            return new CacheRead<>(false, null);
        }
        if (json.isEmpty()) {
            return new CacheRead<>(true, null);
        }
        try {
            // 用一元素数组读取任意 JSON 根类型：实体、数组、字符串、数字、布尔。
            R value = Convert.convert(type, JSONUtil.parseArray("[" + json + "]").get(0));
            return new CacheRead<>(true, value);
        } catch (RuntimeException e) {
            log.warn("缓存数据无法还原为指定类型，按未命中处理，key={}", key, e);
            return new CacheRead<>(false, null);
        }
    }

    private <R> R load(String key, Supplier<? extends R> loader, CachePolicy policy,
                       Predicate<? super R> shortLived, LoadLimiter limiter, boolean cacheResult) {
        if (!limiter.permits().tryAcquire()) {
            throw new CacheBusyException();
        }
        try {
            // 必须在 Redis 异常捕获范围之外调用；数据库异常不能触发二次回源。
            R value = loader.get();
            if (cacheResult) {
                try {
                    // 空字符串作为 null 标记；真正的空字符串会编码为 JSON 的 ""。
                    String json = value == null ? "" : encode(value);
                    boolean empty = value == null || shortLived.test(value);
                    long ttlMillis = empty ? policy.emptyTtl().toMillis()
                            : policy.ttl().toMillis() + ThreadLocalRandom.current()
                                .nextLong(policy.jitter().toMillis() + 1);
                    redis.opsForValue().set(key, json, ttlMillis, TimeUnit.MILLISECONDS);
                } catch (RuntimeException e) {
                    // 包括 Redis 故障和无法序列化的数据，均保留已成功取得的数据库结果。
                    log.warn("写入缓存失败，返回回源结果，key={}", key, e);
                }
            }
            return value;
        } finally {
            limiter.permits().release();
        }
    }

    private String encode(Object value) {
        // Hutool 对顶层 String 的 toJsonStr 会原样返回；借助数组统一进行 JSON 编码。
        // 去掉外层 [] 后，实体和集合仍沿用原有缓存格式，字符串也能正确转义。
        String array = JSONUtil.toJsonStr(Collections.singletonList(value));
        return array.substring(1, array.length() - 1);
    }

    private LoadLimiter limiter(CachePolicy policy) {
        LoadLimiter limiter = loadLimiters.computeIfAbsent(policy.loadGroup(),
                group -> new LoadLimiter(policy.maxConcurrentLoads(), new Semaphore(policy.maxConcurrentLoads())));
        if (limiter.limit() != policy.maxConcurrentLoads()) {
            throw new IllegalArgumentException("同一 loadGroup 的回源并发上限必须一致：" + policy.loadGroup());
        }
        return limiter;
    }

    private void unlock(RLock lock) {
        try {
            // Redisson 原子校验客户端和线程的所有权；不能用 forceUnlock 删除他人的锁。
            lock.unlock();
        } catch (RedisException | IllegalMonitorStateException e) {
            log.warn("释放缓存重建锁失败或所有权已丢失，key={}", lock.getName(), e);
        }
    }

    public <ID> void evictAfterCommit(String prefix, ID id, CachePolicy policy) {
        evictAfterCommit(key(prefix, id), policy);
    }

    /** 在事务成功提交后删除；调用方没有事务时立即删除。重试任务不持久化。 */
    public void evictAfterCommit(String key, CachePolicy policy) {
        requireKey(key);
        Objects.requireNonNull(policy, "policy");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evictWithRetry(key, policy);
                }
            });
        } else {
            evictWithRetry(key, policy);
        }
    }

    private void evictWithRetry(String key, CachePolicy policy) {
        for (int attempt = 1; attempt <= policy.deleteAttempts(); attempt++) {
            try {
                redis.delete(key);
                return;
            } catch (DataAccessException e) {
                log.warn("删除缓存失败，key={}，attempt={}", key, attempt, e);
                if (attempt < policy.deleteAttempts() && !pause(policy)) {
                    break;
                }
            }
        }
        log.error("缓存失效重试结束，依赖 TTL 过期，key={}", key);
    }

    private boolean pause(CachePolicy policy) {
        try {
            Thread.sleep(policy.retryDelay().toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String key(String prefix, Object id) {
        requireKey(prefix);
        return prefix + Objects.requireNonNull(id, "id");
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("缓存 key/前缀不能为空");
        }
    }

    private record CacheRead<R>(boolean hit, R value) { }
    private record LoadLimiter(int limit, Semaphore permits) { }
}
