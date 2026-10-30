package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.hmdp.dto.BlogLikeStatus;
import com.hmdp.mapper.BlogLikeMapper;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.repository.BlogLikeRedisRepository;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** 数据库仅写当前连接临时表；Redis 仅写随机前缀，不启动业务消费者。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "hmdp.integration", matches = "true")
class BlogLikeIntegrationTest {
    private Connection connection;
    private JdbcTemplate jdbc;
    private LettuceConnectionFactory redisFactory;
    private StringRedisTemplate redis;
    private BlogLikeRedisRepository likes;
    private BlogLikePersistenceService persistence;
    private String prefix;

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
        String blogDefinition = jdbc.queryForMap("SHOW CREATE TABLE tb_blog").get("Create Table").toString();
        jdbc.execute(blogDefinition.replaceFirst("CREATE TABLE", "CREATE TEMPORARY TABLE"));
        String migration = Files.readString(Path.of("sql/migrations/20261030_add_blog_like.sql"))
                .replaceAll("(?m)^\\s*--.*$", "")
                .replace("CREATE TABLE IF NOT EXISTS", "CREATE TEMPORARY TABLE");
        for (String sql : migration.split(";")) if (!sql.isBlank()) jdbc.execute(sql);

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(BlogMapper.class);
        configuration.addMapper(BlogLikeMapper.class);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
        BlogLikePersistenceService target = new BlogLikePersistenceService();
        ReflectionTestUtils.setField(target, "blogMapper", session.getMapper(BlogMapper.class));
        ReflectionTestUtils.setField(target, "blogLikeMapper", session.getMapper(BlogLikeMapper.class));
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource),
                new AnnotationTransactionAttributeSource()));
        persistence = (BlogLikePersistenceService) proxy.getProxy();

        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(
                env.getRequiredProperty("spring.data.redis.host"), env.getProperty("spring.data.redis.port", Integer.class, 6379));
        config.setDatabase(env.getProperty("spring.data.redis.database", Integer.class, 0));
        String password = env.getProperty("spring.data.redis.password");
        String username = env.getProperty("spring.data.redis.username");
        if (password != null && !password.isBlank()) config.setPassword(password);
        if (username != null && !username.isBlank()) config.setUsername(username);
        redisFactory = new LettuceConnectionFactory(config);
        redisFactory.afterPropertiesSet();
        redisFactory.start();
        redis = new StringRedisTemplate(redisFactory);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM tb_blog_like");
        jdbc.update("DELETE FROM tb_blog_like_sync");
        jdbc.update("DELETE FROM tb_blog");
        jdbc.update("INSERT INTO tb_blog(id,shop_id,user_id,title,images,content,liked) VALUES(1,1,1,'test','test.jpg','test',10)");
        prefix = "hmdp:test:blog-like:" + UUID.randomUUID() + ":";
        likes = new BlogLikeRedisRepository(redis, prefix);
        likes.initialize(persistence.loadSeed(1));
    }

    @AfterEach
    void cleanRedis() {
        if (likes != null) {
            Set<String> testKeys = new HashSet<>();
            for (int id = 1; id <= 3; id++) testKeys.addAll(likes.keys(id));
            redis.delete(testKeys);
        }
    }

    @AfterAll
    void close() throws Exception {
        if (redisFactory != null) redisFactory.destroy();
        if (connection != null) connection.close();
    }

    @Test
    void duplicateRequestsAreIdempotentAndUnlikeIsPersistedInTheSameWay() {
        assertEquals(new BlogLikeStatus(10, false), likes.change(1, 2, false));
        assertTrue(likes.dirtyIds(100).isEmpty());
        assertEquals(new BlogLikeStatus(11, true), likes.change(1, 2, true));
        assertEquals(new BlogLikeStatus(11, true), likes.change(1, 2, true));
        assertEquals(10, databaseTotal(), "请求线程不应直接写数据库");
        flush();
        assertEquals(11, databaseTotal());
        assertEquals(1, activeUsers());
        assertEquals(new BlogLikeStatus(10, false), likes.change(1, 2, false));
        assertEquals(new BlogLikeStatus(10, false), likes.change(1, 2, false));
        flush();
        assertEquals(10, databaseTotal());
        assertEquals(0, activeUsers());
    }

    @Test
    void concurrentRequestsFromTheSameUserOnlyAddOneLike() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<BlogLikeStatus>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 60; i++) results.add(pool.submit(() -> {
                start.await();
                return likes.change(1, 2, true);
            }));
            start.countDown();
            for (Future<BlogLikeStatus> result : results) assertEquals(new BlogLikeStatus(11, true), result.get(10, TimeUnit.SECONDS));
            flush();
            assertEquals(11, databaseTotal());
            assertEquals(1, activeUsers());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void newChangeDuringFlushSurvivesOldAcknowledgementAndRetry() {
        likes.change(1, 2, true);
        BlogLikeSnapshot old = likes.snapshot(1);
        likes.change(1, 2, false);
        persistence.persist(old);
        likes.acknowledge(old);
        assertNotNull(redis.opsForZSet().score(likes.keys(1).get(2), "1"));
        BlogLikeSnapshot latest = likes.snapshot(1);
        persistence.persist(latest);
        persistence.persist(latest); // 提交后确认失败的重试。
        persistence.persist(old); // 更旧的批次不能覆盖新状态。
        likes.acknowledge(latest);
        assertEquals(10, databaseTotal());
        assertEquals(0, activeUsers());
        assertTrue(likes.dirtyIds(100).isEmpty());
    }

    @Test
    void differentUsersAndMultipleOperationsAreCoalescedIntoFinalStates() {
        likes.change(1, 2, true);
        likes.change(1, 3, true);
        likes.change(1, 2, false);
        likes.change(1, 2, true);
        assertEquals(2, likes.snapshot(1).changes().size());
        flush();
        assertEquals(12, databaseTotal());
        assertEquals(2, activeUsers());
    }

    @Test
    void redisCanBeRebuiltFromCommittedRelationsWithoutResettingHistoricalCount() {
        likes.change(1, 2, true);
        flush();
        redis.delete(likes.keys(1));
        likes.initialize(persistence.loadSeed(1));
        assertEquals(new BlogLikeStatus(11, true), likes.read(1, 2L));
        assertEquals(new BlogLikeStatus(11, true), likes.change(1, 2, true));
        likes.change(1, 2, false);
        flush();
        assertEquals(10, databaseTotal());
    }

    @Test
    void databaseFailureRollsBackRelationsCountAndVersion() {
        likes.change(1, 2, true);
        // 删除笔记模拟落库过程中业务对象缺失，用户关系写入也必须回滚。
        jdbc.update("DELETE FROM tb_blog WHERE id=1");
        assertThrows(IllegalStateException.class, () -> persistence.persist(likes.snapshot(1)));
        assertEquals(0, activeUsers());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM tb_blog_like_sync", Integer.class));
        assertFalse(likes.dirtyIds(100).isEmpty());
    }

    @Test
    void partialRedisLossCannotSilentlyReplacePendingChangesWithOldDatabaseState() {
        likes.change(1, 2, true);
        redis.delete(likes.keys(1).get(0));
        assertThrows(RuntimeException.class, () -> likes.initialize(persistence.loadSeed(1)));
        assertEquals(10, databaseTotal());
    }

    @Test
    void continuouslyHotBlogsYieldTheNextSlotToOtherReadyBlogs() {
        for (int id = 2; id <= 3; id++) {
            jdbc.update("INSERT INTO tb_blog(id,shop_id,user_id,title,images,content,liked) VALUES(?,1,1,'test','test.jpg','test',0)", id);
            likes.initialize(persistence.loadSeed(id));
        }
        for (int id = 1; id <= 3; id++) {
            likes.change(id, 2, true);
            // 排队顺序固定，不依赖操作是否恰好发生在同一毫秒。
            redis.opsForZSet().add(likes.keys(id).get(2), Integer.toString(id), id);
        }
        for (int expected = 1; expected <= 3; expected++) {
            long selected = Long.parseLong(likes.dirtyIds(1).iterator().next());
            assertEquals(expected, selected, "前一篇持续收到新点赞，也必须让出同步名额");
            BlogLikeSnapshot snapshot = likes.snapshot(selected, 1);
            likes.change(selected, 3, true); // 每轮快照后、ACK 前都有新操作。
            persistence.persist(snapshot);
            likes.acknowledge(snapshot);
            assertEquals(1, redis.opsForHash().size(likes.keys(selected).get(1)));
        }
        assertEquals(11, databaseTotal());
        assertEquals(1, jdbc.queryForObject("SELECT liked FROM tb_blog WHERE id=2", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT liked FROM tb_blog WHERE id=3", Integer.class));
    }

    @Test
    void committedUsersAreRemovedDuringFortyConsecutiveRoundsWithNewWriters() {
        likes.change(1, 2, true);
        for (long nextUser = 3; nextUser <= 42; nextUser++) {
            BlogLikeSnapshot snapshot = likes.snapshot(1, 2);
            likes.change(1, nextUser, true);
            persistence.persist(snapshot);
            likes.acknowledge(snapshot);
            assertEquals(1, redis.opsForHash().size(likes.keys(1).get(1)), "已提交用户不能在后续批次中持续膨胀");
            assertEquals(1, redis.opsForZSet().size(likes.keys(1).get(3)));
            assertFalse(redis.opsForHash().hasKey(likes.keys(1).get(1), Long.toString(nextUser - 1)));
        }
        flush();
        assertEquals(51, databaseTotal());
        assertEquals(41, activeUsers());
        assertEquals(0, redis.opsForHash().size(likes.keys(1).get(1)));
    }

    @Test
    void boundedSlicesAtTheSameGlobalVersionAllCommitWithoutDoubleCounting() {
        for (long user = 2; user <= 24; user++) likes.change(1, user, true);
        BlogLikeSnapshot first = null;
        int processed = 0;
        while (redis.opsForHash().size(likes.keys(1).get(1)) > 0) {
            BlogLikeSnapshot snapshot = likes.snapshot(1, 3);
            if (first == null) first = snapshot;
            assertEquals(23, snapshot.version(), "无新操作时，后续分批共用全局版本");
            assertTrue(snapshot.changes().size() <= 3);
            persistence.persist(snapshot);
            persistence.persist(snapshot); // 提交成功但确认丢失。
            likes.acknowledge(snapshot);
            processed += snapshot.changes().size();
            assertEquals(10 + processed, databaseTotal());
            BlogLikeSeed committed = persistence.loadSeed(1);
            assertEquals(processed, committed.users().size());
            assertEquals(10 + processed, committed.liked());
        }
        assertEquals(23, processed);
        persistence.persist(first); // 最旧分批重放也不能把新状态改回去。
        assertEquals(33, databaseTotal());
        assertEquals(23, activeUsers());
    }

    @Test
    void ackRemovesOnlyUnchangedUsersAndPreservesANewerCancellation() {
        likes.change(1, 2, true);
        likes.change(1, 3, true);
        BlogLikeSnapshot snapshot = likes.snapshot(1, 2);
        likes.change(1, 2, false);
        persistence.persist(snapshot);
        likes.acknowledge(snapshot);
        assertTrue(redis.opsForHash().hasKey(likes.keys(1).get(1), "2"));
        assertFalse(redis.opsForHash().hasKey(likes.keys(1).get(1), "3"));
        flush();
        persistence.persist(snapshot);
        assertEquals(11, databaseTotal());
        assertEquals(1, activeUsers());
    }

    @Test
    void aContinuouslyChangingUserDoesNotOccupyEveryUserSlice() {
        likes.change(1, 2, true);
        likes.change(1, 3, true);
        BlogLikeSnapshot first = likes.snapshot(1, 1);
        assertEquals(2, first.changes().get(0).userId());
        likes.change(1, 2, false);
        persistence.persist(first);
        likes.acknowledge(first);
        BlogLikeSnapshot second = likes.snapshot(1, 1);
        assertEquals(3, second.changes().get(0).userId());
        persistence.persist(second);
        likes.acknowledge(second);
        flush();
        assertEquals(11, databaseTotal());
        assertEquals(1, activeUsers());
    }

    @Test
    void legacyPendingValuesAreUpgradedWithoutDroppingOldOrNewWrites() {
        likes.change(1, 2, true);
        redis.opsForHash().put(likes.keys(1).get(1), "2", "1");
        redis.opsForHash().delete(likes.keys(1).get(0), "_indexed");
        redis.delete(likes.keys(1).get(3));
        likes.change(1, 3, true);
        BlogLikeSnapshot snapshot = likes.snapshot(1, 1);
        while (snapshot == null) snapshot = likes.snapshot(1, 1);
        while (redis.opsForHash().size(likes.keys(1).get(1)) > 0) {
            persistence.persist(snapshot);
            likes.acknowledge(snapshot);
            if (redis.opsForHash().size(likes.keys(1).get(1)) > 0) snapshot = likes.snapshot(1, 1);
        }
        assertEquals(12, databaseTotal());
        assertEquals(2, activeUsers());
    }

    @Test
    void rankingUsesChangeVersionsAndDuplicatePutNeverMovesTheUser() {
        likes.change(1, 9, true);
        likes.change(1, 2, true);
        likes.change(1, 7, true);
        rebuildRanking();
        assertEquals(List.of(9L, 2L, 7L), likes.rankedUsers(1, 3));
        Double score = redis.opsForZSet().score(likes.keys(1).get(4), "9");
        likes.change(1, 9, true);
        assertEquals(score, redis.opsForZSet().score(likes.keys(1).get(4), "9"));
        assertEquals("3", redis.opsForHash().get(likes.keys(1).get(0), "_version"));
        likes.change(1, 2, false);
        assertEquals(List.of(9L, 7L), likes.rankedUsers(1, 3));
        likes.change(1, 2, true);
        assertEquals(List.of(9L, 7L, 2L), likes.rankedUsers(1, 3));
        assertEquals(5D, redis.opsForZSet().score(likes.keys(1).get(4), "2"));
    }

    @Test
    void coldIndexRestoresCommittedVersionsInsteadOfAssigningNewScores() {
        likes.change(1, 9, true);
        likes.change(1, 7, true);
        likes.change(1, 2, true);
        flush();
        redis.delete(likes.keys(1));
        likes.initialize(persistence.loadSeed(1));
        assertNull(likes.rankedUsers(1, 3));
        rebuildRanking();
        assertEquals(List.of(9L, 7L, 2L), likes.rankedUsers(1, 3));
        assertEquals(1D, redis.opsForZSet().score(likes.keys(1).get(4), "9"));
        likes.change(1, 9, true);
        assertEquals(1D, redis.opsForZSet().score(likes.keys(1).get(4), "9"));
        assertEquals(13, databaseTotal());
    }

    @Test
    void rankRebuildIncludesUnflushedLikesAndCancellationsWithoutChangingCoreData() {
        likes.change(1, 9, true);
        flush();
        likes.change(1, 2, true);
        likes.change(1, 9, false);
        rebuildRanking();
        assertEquals(List.of(2L), likes.rankedUsers(1, 3));
        assertEquals(2, redis.opsForHash().size(likes.keys(1).get(1)));
        assertEquals("3", redis.opsForHash().get(likes.keys(1).get(0), "_version"));
        redis.delete(likes.keys(1).get(4));
        assertNull(likes.rankedUsers(1, 3));
        assertEquals(new BlogLikeStatus(11, true), likes.change(1, 2, true));
        rebuildRanking();
        assertEquals(List.of(2L), likes.rankedUsers(1, 3));
        assertEquals(2D, redis.opsForZSet().score(likes.keys(1).get(4), "2"));
    }

    @Test
    void concurrentMutationsDuringRebuildCannotBeOverwrittenByOldSeeds() {
        likes.change(1, 9, true);
        likes.change(1, 2, true);
        flush();
        List<BlogLikeChange> oldSeed = persistence.loadRankUsers(1);
        String token = UUID.randomUUID().toString();
        likes.beginRanking(1, token);
        likes.change(1, 9, false);
        likes.change(1, 2, false);
        likes.change(1, 2, true);
        likes.change(1, 7, true);
        likes.seedRanking(1, token, oldSeed);
        likes.seedPendingRanking(1, token);
        likes.change(1, 8, true);
        likes.change(1, 7, false);
        assertNull(likes.rankedUsers(1, 3), "临时榜单不能被查询为完整榜单");
        likes.publishRanking(1, token);
        assertEquals(List.of(2L, 8L), likes.rankedUsers(1, 3));
        assertEquals(5D, redis.opsForZSet().score(likes.keys(1).get(4), "2"));
        assertEquals(7D, redis.opsForZSet().score(likes.keys(1).get(4), "8"));
        assertEquals(-1, redis.getExpire(likes.keys(1).get(4)));
    }

    @Test
    void anInitializedEmptyRankingIsDistinctFromAnUninitializedIndex() {
        assertNull(likes.rankedUsers(1, 3));
        rebuildRanking();
        assertEquals(List.of(), likes.rankedUsers(1, 3));
        assertEquals(10, likes.read(1, null).liked(), "历史计数不能用来虚构点赞用户");
    }

    @Test
    void obsoleteRebuildCannotPublishOrAbortANewerBuild() {
        String oldToken = UUID.randomUUID().toString();
        String currentToken = UUID.randomUUID().toString();
        likes.beginRanking(1, oldToken);
        likes.beginRanking(1, currentToken);
        likes.change(1, 9, true);
        assertThrows(RuntimeException.class, () -> likes.publishRanking(1, oldToken));
        likes.abortRanking(1, oldToken);
        likes.publishRanking(1, currentToken);
        assertEquals(List.of(9L), likes.rankedUsers(1, 3));
    }

    @Test
    void corruptedDerivedIndexDoesNotDecideWhetherTheUserHasLiked() {
        rebuildRanking();
        redis.opsForValue().set(likes.keys(1).get(4), "invalid index type");
        assertEquals(new BlogLikeStatus(11, true), likes.change(1, 9, true));
        assertEquals(new BlogLikeStatus(11, true), likes.change(1, 9, true));
        rebuildRanking();
        assertEquals(List.of(9L), likes.rankedUsers(1, 3));
    }

    private void rebuildRanking() {
        String token = UUID.randomUUID().toString();
        likes.beginRanking(1, token);
        likes.seedRanking(1, token, persistence.loadRankUsers(1));
        likes.seedPendingRanking(1, token);
        likes.publishRanking(1, token);
    }

    private void flush() {
        BlogLikeSnapshot snapshot = likes.snapshot(1);
        persistence.persist(snapshot);
        likes.acknowledge(snapshot);
    }

    private int databaseTotal() { return jdbc.queryForObject("SELECT liked FROM tb_blog WHERE id=1", Integer.class); }
    private int activeUsers() { return jdbc.queryForObject("SELECT COUNT(*) FROM tb_blog_like WHERE liked=1", Integer.class); }
}
