package com.hmdp.service.impl;

import com.hmdp.config.StatisticsProperties;
import com.hmdp.dto.*;
import com.hmdp.repository.StatisticsRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.env.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.redis.connection.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.hmdp.dto.UvDaily.Availability.*;
import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "hmdp.integration", matches = "true")
class StatisticsIntegrationTest {
    private LettuceConnectionFactory factory; private StringRedisTemplate redis;
    private StatisticsRepository repository; private StatisticsProperties properties;
    private final String prefix = "hmdp:test:uv:" + UUID.randomUUID() + ":";
    private final Set<String> keys = ConcurrentHashMap.newKeySet();
    @BeforeAll void connect() {
        StandardEnvironment env = new StandardEnvironment();
        for (String path : List.of("src/main/resources/application.yaml", "config/application-secret.yml")) {
            FileSystemResource resource = new FileSystemResource(path); if (!resource.exists()) continue;
            YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean(); yaml.setResources(resource);
            env.getPropertySources().addFirst(new PropertiesPropertySource(path, yaml.getObject()));
        }
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(env.getRequiredProperty("spring.data.redis.host"), env.getProperty("spring.data.redis.port", Integer.class, 6379));
        config.setDatabase(env.getProperty("spring.data.redis.database", Integer.class, 0));
        String password = env.getProperty("spring.data.redis.password"), username = env.getProperty("spring.data.redis.username");
        if (password != null && !password.isBlank()) config.setPassword(password);
        if (username != null && !username.isBlank()) config.setUsername(username);
        factory = new LettuceConnectionFactory(config); factory.afterPropertiesSet(); factory.start(); redis = new StringRedisTemplate(factory);
        repository = new StatisticsRepository(redis, prefix);
    }
    @BeforeEach void setup() { properties = new StatisticsProperties(); properties.setStartDate("2026-10-01"); }
    @AfterEach void clean() { redis.delete(keys); keys.clear(); }
    @AfterAll void close() { if (factory != null) factory.destroy(); }
    private StatisticsService at(String date) {
        LocalDate day = LocalDate.parse(date); keys.add(repository.key(day));
        return new StatisticsService(repository, properties, Clock.fixed(day.atTime(12, 0).atZone(ZoneId.of("Asia/Shanghai")).toInstant(), ZoneOffset.UTC));
    }
    @Test void sameVisitorRepeatsAndDistinctVisitorsUseActualHyperLogLog() {
        StatisticsService service = at("2026-11-03"); String visitor = UUID.randomUUID().toString();
        assertTrue(service.record(visitor).getSuccess()); assertTrue(service.record(visitor).getSuccess());
        assertEquals(1L, ((UvDaily) service.daily(null).getData()).uv());
        assertTrue(service.record(UUID.randomUUID().toString()).getSuccess());
        assertEquals(2L, ((UvDaily) service.daily(null).getData()).uv());
    }
    @Test void multiDayUnionDeduplicatesInsteadOfAddingDailyCounts() {
        String a = UUID.randomUUID().toString(), b = UUID.randomUUID().toString(), c = UUID.randomUUID().toString();
        at("2026-11-01").record(a); at("2026-11-01").record(b);
        at("2026-11-02").record(a); at("2026-11-02").record(c); at("2026-11-03").record(a);
        UvRecent result = (UvRecent) at("2026-11-03").recent().getData();
        assertTrue(result.complete()); assertEquals(3L, result.rangeUv());
        assertEquals(5L, result.list().stream().mapToLong(UvDaily::uv).sum());
    }
    @Test void repeatedVisitsHaveTheSameDateDerivedExpiration() {
        StatisticsService service = at("2026-11-03"); String visitor = UUID.randomUUID().toString();
        assertTrue(service.record(visitor).getSuccess()); String key = repository.key(LocalDate.of(2026, 11, 3));
        long expected = LocalDate.of(2026, 11, 3).plusDays(90).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
        Long now = redis.execute((RedisCallback<Long>) connection -> connection.serverCommands().time());
        assertNotNull(now); assertTrue(Math.abs(now + redis.getExpire(key, TimeUnit.MILLISECONDS) - expected) < 2000);
        assertTrue(service.record(visitor).getSuccess());
        Long after = redis.execute((RedisCallback<Long>) connection -> connection.serverCommands().time());
        assertTrue(Math.abs(after + redis.getExpire(key, TimeUnit.MILLISECONDS) - expected) < 2000);
    }
    @Test void concurrentDuplicateEventsRemainOneSmallSampleVisitor() throws Exception {
        StatisticsService service = at("2026-11-03"); String visitor = UUID.randomUUID().toString();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try { List<Future<Result>> results = new ArrayList<>();
            for (int i = 0; i < 24; i++) results.add(pool.submit(() -> service.record(visitor)));
            for (Future<Result> result : results) assertTrue(result.get(10, TimeUnit.SECONDS).getSuccess());
            assertEquals(1L, ((UvDaily) service.daily(null).getData()).uv());
        } finally { pool.shutdownNow(); }
    }
    @Test void wrongKeyTypesAndCorruptStringsFailWithoutOverwritingThem() {
        StatisticsService service = at("2026-11-03"); String key = repository.key(LocalDate.of(2026, 11, 3));
        redis.opsForHash().put(key, "original", "preserved");
        assertFalse(service.record(UUID.randomUUID().toString()).getSuccess()); assertFalse(service.daily(null).getSuccess());
        assertEquals("preserved", redis.opsForHash().get(key, "original")); redis.delete(key);
        redis.opsForValue().set(key, "not a HyperLogLog");
        assertFalse(service.record(UUID.randomUUID().toString()).getSuccess()); assertFalse(service.recent().getSuccess());
        assertEquals("not a HyperLogLog", redis.opsForValue().get(key));
    }
    @Test void partialCoverageEmptyAndExpiredAreDistinctAndReadsDoNotCreateKeys() {
        properties.setStartDate("2026-11-03"); StatisticsService service = at("2026-11-03");
        assertEquals(new UvDaily("2026-11-03", 0L, true, AVAILABLE), service.daily(null).getData());
        assertFalse(Boolean.TRUE.equals(redis.hasKey(repository.key(LocalDate.of(2026, 11, 3)))));
        UvRecent recent = (UvRecent) service.recent().getData(); assertFalse(recent.complete()); assertEquals(0L, recent.rangeUv());
        assertEquals(NOT_COLLECTED, recent.list().get(0).status()); assertNull(recent.list().get(0).uv());
        properties.setStartDate("2026-01-01");
        assertEquals(EXPIRED, ((UvDaily) service.daily("2026-08-05").getData()).status());
    }
    @Test void shanghaiMidnightAndMonthBoundaryUseDifferentDailyKeys() {
        String visitor = UUID.randomUUID().toString(); keys.add(repository.key(LocalDate.of(2026, 10, 31))); keys.add(repository.key(LocalDate.of(2026, 11, 1)));
        StatisticsService before = new StatisticsService(repository, properties, Clock.fixed(Instant.parse("2026-10-31T15:59:59.999Z"), ZoneOffset.UTC));
        StatisticsService after = new StatisticsService(repository, properties, Clock.fixed(Instant.parse("2026-10-31T16:00:00Z"), ZoneOffset.UTC));
        assertTrue(before.record(visitor).getSuccess()); assertTrue(after.record(visitor).getSuccess());
        assertEquals(1L, ((UvDaily) before.daily(null).getData()).uv()); assertEquals(1L, ((UvDaily) after.daily(null).getData()).uv());
        assertEquals(1L, ((UvRecent) after.recent().getData()).rangeUv());
    }
}
