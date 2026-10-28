package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.hmdp.config.SeckillScriptConfig;
import com.hmdp.entity.VoucherOrderTask;
import com.hmdp.mapper.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** 显式开启后连接本机配置；数据库写入仅发生在当前连接的临时表，Redis 仅使用随机测试前缀。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "hmdp.integration", matches = "true")
class VoucherOrderIntegrationTest {
    private Connection connection;
    private JdbcTemplate jdbc;
    private VoucherOrderPersistenceService persistence;
    private LettuceConnectionFactory redisFactory;
    private StringRedisTemplate redis;
    private List<String> keys;
    private String nextResultKey;
    private final SeckillScriptConfig scripts = new SeckillScriptConfig();

    @BeforeAll
    void connect() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        for (String path : List.of("src/main/resources/application.yaml", "config/application-secret.yml")) {
            FileSystemResource resource = new FileSystemResource(path);
            if (!resource.exists()) continue;
            YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
            yaml.setResources(resource);
            env.getPropertySources().addFirst(new PropertiesPropertySource(path, yaml.getObject()));
        }
        connection = DriverManager.getConnection(env.getRequiredProperty("spring.datasource.url"),
                env.getRequiredProperty("spring.datasource.username"), env.getRequiredProperty("spring.datasource.password"));
        assertEquals("hmdp", connection.getCatalog());
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(connection, true);
        jdbc = new JdbcTemplate(dataSource);
        // 同名临时表遮蔽业务表，关闭连接即自动删除；后续事务全部复用此连接。
        for (String table : List.of("tb_voucher_order", "tb_seckill_voucher", "tb_voucher_order_task")) {
            String definition = jdbc.queryForMap("SHOW CREATE TABLE " + table).get("Create Table").toString();
            jdbc.execute(definition.replaceFirst("CREATE TABLE", "CREATE TEMPORARY TABLE"));
        }
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(VoucherOrderMapper.class);
        configuration.addMapper(SeckillVoucherMapper.class);
        configuration.addMapper(VoucherOrderTaskMapper.class);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
        VoucherOrderPersistenceService target = new VoucherOrderPersistenceService();
        ReflectionTestUtils.setField(target, "voucherOrderMapper", session.getMapper(VoucherOrderMapper.class));
        ReflectionTestUtils.setField(target, "seckillVoucherMapper", session.getMapper(SeckillVoucherMapper.class));
        ReflectionTestUtils.setField(target, "taskMapper", session.getMapper(VoucherOrderTaskMapper.class));
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource),
                new AnnotationTransactionAttributeSource()));
        persistence = (VoucherOrderPersistenceService) proxy.getProxy();

        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(
                env.getRequiredProperty("spring.data.redis.host"), env.getProperty("spring.data.redis.port", Integer.class, 6379));
        config.setDatabase(env.getProperty("spring.data.redis.database", Integer.class, 0));
        String password = env.getProperty("spring.data.redis.password");
        if (password != null && !password.isBlank()) config.setPassword(password);
        String username = env.getProperty("spring.data.redis.username");
        if (username != null && !username.isBlank()) config.setUsername(username);
        redisFactory = new LettuceConnectionFactory(config);
        redisFactory.afterPropertiesSet();
        redisFactory.start();
        redis = new StringRedisTemplate(redisFactory);
    }

    @BeforeEach
    void resetFixtures() {
        // 这些名称此时只解析到当前连接的临时表。
        jdbc.update("DELETE FROM tb_voucher_order");
        jdbc.update("DELETE FROM tb_voucher_order_task");
        jdbc.update("DELETE FROM tb_seckill_voucher");
        jdbc.update("INSERT INTO tb_seckill_voucher (voucher_id,stock,begin_time,end_time) VALUES (2,2,NOW(),DATE_ADD(NOW(),INTERVAL 1 DAY))");
        String prefix = "hmdp:test:order-result:" + UUID.randomUUID() + ":";
        keys = List.of(prefix + "stock", prefix + "users", prefix + "meta", prefix + "stream",
                prefix + "result:100", prefix + "reservations");
        nextResultKey = prefix + "result:101";
        redis.opsForValue().set(keys.get(0), "2");
        redis.opsForHash().putAll(keys.get(2), Map.of("beginTime", "0", "endTime", "9999999999999"));
    }

    @AfterEach
    void removeRedisFixtures() {
        if (redis != null && keys != null) {
            redis.delete(keys);
            redis.delete(nextResultKey);
        }
    }

    @AfterAll
    void close() throws Exception {
        if (redisFactory != null) redisFactory.destroy();
        if (connection != null) connection.close();
    }

    @Test
    void duplicateDeliveryCommitsOneOrderAndOneStockDeduction() {
        assertEquals(VoucherOrderTask.SUCCESS, persistence.persistVoucherOrder(100, 1, 2).getStatus());
        assertEquals(VoucherOrderTask.SUCCESS, persistence.persistVoucherOrder(100, 1, 2).getStatus());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=2", Integer.class));
    }

    @Test
    void actualTransactionRollsBackOrderAndTaskAndFailureCannotLaterResurrect() {
        jdbc.update("UPDATE tb_seckill_voucher SET stock=0 WHERE voucher_id=2");
        assertThrows(IllegalStateException.class, () -> persistence.persistVoucherOrder(100, 1, 2));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order_task", Integer.class));
        persistence.recordFailure(100, 1, 2);
        persistence.recordFailure(100, 1, 2);
        assertEquals(VoucherOrderTask.COMPENSATING, persistence.recordFailure(100, 1, 2).getStatus());
        jdbc.update("UPDATE tb_seckill_voucher SET stock=2 WHERE voucher_id=2");
        assertEquals(VoucherOrderTask.COMPENSATING, persistence.persistVoucherOrder(100, 1, 2).getStatus());
        persistence.completeCompensation(100);
        assertEquals(VoucherOrderTask.FAILED, persistence.persistVoucherOrder(100, 1, 2).getStatus());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_voucher_order", Integer.class));
    }

    @Test
    void anotherSuccessfulOrderNeverReleasesPurchaseQualification() {
        persistence.persistVoucherOrder(99, 1, 2);
        assertThrows(Exception.class, () -> persistence.persistVoucherOrder(100, 1, 2));
        VoucherOrderTask failed = persistence.recordFailure(100, 1, 2);
        assertEquals(VoucherOrderTask.COMPENSATING, failed.getStatus());
        assertFalse(failed.getRestoreQualification());
        assertEquals(1, jdbc.queryForObject("SELECT stock FROM tb_seckill_voucher WHERE voucher_id=2", Integer.class));
    }

    @Test
    void repeatedCompensationCannotOverRefundOrRemoveNewReservation() {
        assertEquals(0L, reserve(keys, "100"));
        assertEquals("1", redis.opsForValue().get(keys.get(0)));
        assertEquals(1L, compensate("1", "100", "1"));
        assertEquals("2", redis.opsForValue().get(keys.get(0)));
        List<String> newKeys = new ArrayList<>(keys);
        newKeys.set(4, nextResultKey);
        assertEquals(0L, reserve(newKeys, "101"));
        assertEquals(1L, compensate("1", "100", "1"));
        assertEquals("1", redis.opsForValue().get(keys.get(0)));
        assertEquals("101", redis.opsForHash().get(keys.get(5), "1"));
        assertTrue(redis.opsForSet().isMember(keys.get(1), "1"));
    }

    @Test
    void compensationCanRefundStockWithoutRestoringQualification() {
        assertEquals(0L, reserve(keys, "100"));
        assertEquals(1L, compensate("1", "100", "0"));
        assertEquals("2", redis.opsForValue().get(keys.get(0)));
        assertTrue(redis.opsForSet().isMember(keys.get(1), "1"));
        List<String> newKeys = new ArrayList<>(keys); newKeys.set(4, nextResultKey);
        assertEquals(2L, reserve(newKeys, "101"));
    }

    @Test
    void wrongOwnerOrMissingStockCannotTriggerRefund() {
        assertEquals(0L, reserve(keys, "100"));
        assertEquals(-1L, compensate("9", "100", "1"));
        assertEquals("1", redis.opsForValue().get(keys.get(0)));
        redis.delete(keys.get(0));
        assertEquals(-1L, compensate("1", "100", "1"));
        assertFalse(redis.hasKey(keys.get(0)));
    }

    @Test
    void invalidStreamTypeIsRejectedBeforeAnyReservationMutation() {
        redis.opsForValue().set(keys.get(3), "wrong type");
        assertEquals(3L, reserve(keys, "100"));
        assertEquals("2", redis.opsForValue().get(keys.get(0)));
        assertFalse(redis.hasKey(keys.get(1)));
        assertFalse(redis.hasKey(keys.get(4)));
    }

    private Long reserve(List<String> currentKeys, String orderId) {
        return redis.execute(scripts.seckillScript(), currentKeys, "2", "1", orderId, "1000");
    }

    private Long compensate(String userId, String orderId, String restore) {
        return redis.execute(scripts.compensateSeckillScript(),
                List.of(keys.get(0), keys.get(1), keys.get(5), keys.get(4)), userId, orderId, restore);
    }
}
