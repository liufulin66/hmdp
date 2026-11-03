package com.hmdp.service.impl;

import com.hmdp.dto.*;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.env.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** 真实 Redis 位操作和并发；只清理本测试显式记录的随机前缀 key。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "hmdp.integration", matches = "true")
class UserSignIntegrationTest {
    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private final String prefix = "hmdp:test:sign:" + UUID.randomUUID() + ":";
    private final Set<String> keys = ConcurrentHashMap.newKeySet();

    @BeforeAll void connect() {
        StandardEnvironment env = new StandardEnvironment();
        for (String path : List.of("src/main/resources/application.yaml", "config/application-secret.yml")) {
            FileSystemResource resource = new FileSystemResource(path); if (!resource.exists()) continue;
            YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean(); yaml.setResources(resource);
            env.getPropertySources().addFirst(new PropertiesPropertySource(path, yaml.getObject()));
        }
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(env.getRequiredProperty("spring.data.redis.host"),
                env.getProperty("spring.data.redis.port", Integer.class, 6379));
        config.setDatabase(env.getProperty("spring.data.redis.database", Integer.class, 0));
        String password = env.getProperty("spring.data.redis.password"), username = env.getProperty("spring.data.redis.username");
        if (password != null && !password.isBlank()) config.setPassword(password);
        if (username != null && !username.isBlank()) config.setUsername(username);
        factory = new LettuceConnectionFactory(config); factory.afterPropertiesSet(); factory.start();
        redis = new StringRedisTemplate(factory);
        assertEquals("PONG", redis.getConnectionFactory().getConnection().ping());
    }

    @AfterEach void cleanup() { UserHolder.removeUser(); redis.delete(keys); keys.clear(); }
    @AfterAll void close() { if (factory != null) factory.destroy(); }
    private void user(long id) { UserDTO user = new UserDTO(); user.setId(id); UserHolder.saveUser(user); }
    private UserSignService at(String date) {
        user(1001);
        keys.add(prefix + "1001:" + date.substring(0, 7).replace("-", ""));
        return new UserSignService(redis, Clock.fixed(LocalDate.parse(date).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant(), ZoneOffset.UTC), prefix);
    }

    @Test void firstRepeatStatusAndRecreatedServiceKeepTheSameRecordWithoutExpiry() {
        UserSignService service = at("2026-11-03");
        assertEquals(new SignStatus("2026-11-03", false), service.status().getData());
        assertEquals(new SignResult("2026-11-03", true, true), service.sign().getData());
        assertEquals(new SignResult("2026-11-03", true, false), service.sign().getData());
        assertEquals(new SignStatus("2026-11-03", true), at("2026-11-03").status().getData());
        assertEquals(new SignCalendar("2026-11", 30, List.of(3), 1), service.calendar(null).getData());
        assertEquals(-1L, redis.getExpire(prefix + "1001:202611"));
    }

    @Test void actualConcurrentSetBitHasExactlyOneWinnerAndUsersAreIsolated() throws Exception {
        UserSignService service = at("2026-11-03");
        ExecutorService pool = Executors.newFixedThreadPool(12); CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<SignResult>> results = new ArrayList<>();
            for (int i = 0; i < 36; i++) results.add(pool.submit(() -> {
                user(1001); try { start.await(); Result result = service.sign(); assertTrue(result.getSuccess()); return (SignResult) result.getData(); }
                finally { UserHolder.removeUser(); }
            }));
            start.countDown(); int winners = 0;
            for (Future<SignResult> result : results) if (result.get(15, TimeUnit.SECONDS).newSign()) winners++;
            assertEquals(1, winners);
            assertEquals(1, ((SignCalendar) service.calendar(null).getData()).signCount());
            user(1002); keys.add(prefix + "1002:202611");
            assertEquals(new SignStatus("2026-11-03", false), service.status().getData());
            assertEquals(new SignCalendar("2026-11", 30, List.of(), 0), service.calendar(null).getData());
            assertEquals(new SignResult("2026-11-03", true, true), service.sign().getData());
        } finally { pool.shutdownNow(); }
    }

    @Test void realBitFieldParsesMonthLengthsAndIgnoresBitsBeyondTheMonth() {
        for (String month : List.of("2025-02", "2024-02", "2026-01", "2026-04")) {
            int days = YearMonth.parse(month).lengthOfMonth();
            UserSignService service = at(month + "-01"); assertTrue(service.sign().getSuccess());
            assertTrue(at(month + "-" + days).sign().getSuccess());
            String key = prefix + "1001:" + month.replace("-", "");
            assertTrue(Boolean.TRUE.equals(redis.opsForValue().getBit(key, 0)));
            assertTrue(Boolean.TRUE.equals(redis.opsForValue().getBit(key, days - 1)));
            redis.opsForValue().setBit(key, days, true); // 当月有效范围外的脏位不得算入。
            assertEquals(new SignCalendar(month, days, List.of(1, days), 2), service.calendar(null).getData());
        }
    }

    @Test void exactShanghaiMidnightCreatesTheNextMonthAtOffsetZero() {
        UserSignService before = new UserSignService(redis, Clock.fixed(Instant.parse("2026-10-31T15:59:59.999999999Z"), ZoneOffset.UTC), prefix);
        UserSignService after = new UserSignService(redis, Clock.fixed(Instant.parse("2026-10-31T16:00:00Z"), ZoneOffset.UTC), prefix);
        user(1001); keys.add(prefix + "1001:202610"); keys.add(prefix + "1001:202611");
        assertEquals(new SignResult("2026-10-31", true, true), before.sign().getData());
        assertEquals(new SignResult("2026-11-01", true, true), after.sign().getData());
        assertEquals(new SignCalendar("2026-10", 31, List.of(31), 1), after.calendar("2026-10").getData());
        assertEquals(new SignCalendar("2026-11", 30, List.of(1), 1), after.calendar(null).getData());
    }

    @Test void emptyReadDoesNotCreateAKeyAndWrongRedisTypeIsAnExplicitFailure() {
        UserSignService service = at("2026-11-03");
        assertEquals(new SignCalendar("2026-11", 30, List.of(), 0), service.calendar(null).getData());
        assertFalse(Boolean.TRUE.equals(redis.hasKey(prefix + "1001:202611")));
        redis.opsForHash().put(prefix + "1001:202611", "wrong", "type");
        assertFalse(service.sign().getSuccess()); assertFalse(service.status().getSuccess());
        assertFalse(service.calendar(null).getSuccess());
    }
}
