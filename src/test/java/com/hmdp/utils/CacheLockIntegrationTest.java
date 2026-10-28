package com.hmdp.utils;

import com.hmdp.config.RedissonConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 不启动业务消费者；仅写入随机前缀测试 key，并在 finally 中清理。 */
@EnabledIfSystemProperty(named = "hmdp.integration", matches = "true")
class CacheLockIntegrationTest {
    @Test
    void watchdogKeepsSlowRebuildExclusiveAcrossClientsAndUnlockAllowsNextOwner() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        for (String path : List.of("src/main/resources/application.yaml", "config/application-secret.yml")) {
            var resource = new FileSystemResource(path);
            if (!resource.exists()) continue;
            var yaml = new YamlPropertiesFactoryBean();
            yaml.setResources(resource);
            env.getPropertySources().addFirst(new PropertiesPropertySource(path, yaml.getObject()));
        }
        var standalone = new RedisStandaloneConfiguration(env.getRequiredProperty("spring.data.redis.host"),
                env.getProperty("spring.data.redis.port", Integer.class, 6379));
        standalone.setDatabase(env.getProperty("spring.data.redis.database", Integer.class, 0));
        String username = env.getProperty("spring.data.redis.username");
        String password = env.getProperty("spring.data.redis.password");
        if (username != null && !username.isBlank()) standalone.setUsername(username);
        if (password != null && !password.isBlank()) standalone.setPassword(password);
        var factory = new LettuceConnectionFactory(standalone);
        RedissonClient first = null;
        RedissonClient second = null;
        StringRedisTemplate redis = null;
        String prefix = "hmdp:test:cache-lock:" + UUID.randomUUID() + ":";
        String key = prefix + "value";
        String lockKey = prefix + "lock";
        var executor = Executors.newSingleThreadExecutor();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            factory.afterPropertiesSet();
            factory.start();
            redis = new StringRedisTemplate(factory);
            var config = RedissonConfig.lockConfig(factory);
            // 缩短测试周期，持锁时间跨越至少两个完整租期。
            config.setLockWatchdogTimeout(3000);
            first = Redisson.create(config);
            second = Redisson.create(config);
            var firstCache = new CacheClient(redis, first);
            var secondCache = new CacheClient(redis, second);
            var policy = new CachePolicy("integration", Duration.ofMinutes(1), Duration.ofSeconds(10),
                    Duration.ZERO, 2, Duration.ofMillis(20), 2, 1);
            AtomicInteger loads = new AtomicInteger();
            var rebuild = executor.submit(() -> firstCache.queryWithMutex(key, lockKey, String.class, () -> {
                loads.incrementAndGet();
                entered.countDown();
                try {
                    if (!release.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("test timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return "rebuilt";
            }, policy));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            var competingLock = second.getLock(lockKey);
            assertFalse(competingLock.tryLock());
            Thread.sleep(6500);
            assertTrue(redis.getExpire(lockKey, TimeUnit.MILLISECONDS) > 0, "看门狗应持续续期");
            assertFalse(competingLock.tryLock(), "慢查询超过初始租期后仍应互斥");
            assertThrows(CacheBusyException.class, () -> secondCache.queryWithMutex(key, lockKey,
                    String.class, () -> fail("竞争者不能重复回源"), policy));
            release.countDown();
            assertEquals("rebuilt", rebuild.get(5, TimeUnit.SECONDS));
            assertEquals("rebuilt", secondCache.queryWithMutex(key, lockKey, String.class,
                    () -> fail("第二客户端应该读取新缓存"), policy));
            assertEquals(1, loads.get());
            assertTrue(competingLock.tryLock(), "释放后另一个客户端应能获取锁");
            competingLock.unlock();
        } finally {
            release.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
            if (first != null) first.shutdown();
            if (second != null) second.shutdown();
            if (redis != null) redis.delete(List.of(key, lockKey));
            factory.destroy();
        }
    }
}
