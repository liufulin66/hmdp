package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.DynamicTableNameInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.FeedDeliveryTask;
import com.hmdp.mapper.*;
import com.hmdp.repository.FeedRedisRepository;
import com.hmdp.utils.UserHolder;
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
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 随机物理表和随机 Redis 前缀；不启动完整应用，不写业务数据。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "hmdp.integration", matches = "true")
class FeedIntegrationTest {
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;
    private final Map<String, String> tables = new LinkedHashMap<>();
    private final Map<String, Long> before = new HashMap<>();
    private LettuceConnectionFactory redisFactory;
    private StringRedisTemplate redis;
    private FeedRedisRepository inbox;
    private FeedDeliveryTaskMapper tasks;
    private BlogPublicationService publication;
    private BlogServiceImpl blogs;

    @BeforeAll
    void connect() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        for (String path : List.of("src/main/resources/application.yaml", "config/application-secret.yml")) {
            var yaml = new YamlPropertiesFactoryBean();
            var resource = new FileSystemResource(path);
            if (!resource.exists()) continue;
            yaml.setResources(resource);
            env.getPropertySources().addFirst(new PropertiesPropertySource(path, yaml.getObject()));
        }
        var dataSource = new DriverManagerDataSource(env.getRequiredProperty("spring.datasource.url"),
                env.getRequiredProperty("spring.datasource.username"), env.getRequiredProperty("spring.datasource.password"));
        jdbc = new JdbcTemplate(dataSource);
        transactions = new DataSourceTransactionManager(dataSource);
        assertEquals("hmdp", jdbc.queryForObject("SELECT DATABASE()", String.class));
        String suffix = UUID.randomUUID().toString().replace("-", "");
        for (String name : List.of("tb_blog", "tb_follow", "tb_user", "tb_feed_delivery_task")) {
            before.put(name, jdbc.queryForObject("SELECT COUNT(*) FROM " + name, Long.class));
            tables.put(name, "hmdp_test_feed_" + name.substring(3).replace("_", "") + "_" + suffix);
            jdbc.execute("CREATE TABLE " + table(name) + " LIKE " + name);
        }
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(BlogMapper.class);
        configuration.addMapper(UserMapper.class);
        configuration.addMapper(FeedDeliveryTaskMapper.class);
        var names = new DynamicTableNameInnerInterceptor();
        names.setTableNameHandler((sql, name) -> tables.getOrDefault(name, name));
        var interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(names);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        factory.setPlugins(interceptor);
        var session = new SqlSessionTemplate(factory.getObject());
        tasks = session.getMapper(FeedDeliveryTaskMapper.class);
        BlogPublicationService publisher = new BlogPublicationService();
        ReflectionTestUtils.setField(publisher, "blogMapper", session.getMapper(BlogMapper.class));
        ReflectionTestUtils.setField(publisher, "taskMapper", tasks);
        publication = proxy(publisher, BlogPublicationService.class);
        UserServiceImpl users = new UserServiceImpl();
        ReflectionTestUtils.setField(users, "baseMapper", session.getMapper(UserMapper.class));
        blogs = new BlogServiceImpl();
        ReflectionTestUtils.setField(blogs, "baseMapper", session.getMapper(BlogMapper.class));
        ReflectionTestUtils.setField(blogs, "publication", publication);
        ReflectionTestUtils.setField(blogs, "userService", users);
        BlogLikeService likes = mock(BlogLikeService.class);
        doAnswer(call -> { Blog blog = call.getArgument(0); blog.setLiked(77); blog.setIsLike(true); return null; })
                .when(likes).fillLikes(any(Blog.class));
        ReflectionTestUtils.setField(blogs, "blogLikeService", likes);
        var config = new RedisStandaloneConfiguration(env.getRequiredProperty("spring.data.redis.host"),
                env.getProperty("spring.data.redis.port", Integer.class, 6379));
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
    void fixtures() {
        for (String name : tables.keySet()) jdbc.update("DELETE FROM " + table(name));
        for (long id = 1; id <= 10; id++) jdbc.update("INSERT INTO " + table("tb_user")
                + "(id,phone,nick_name,icon) VALUES(?,?,?,?)", id, "test-" + id, "author-" + id, "icon-" + id);
        jdbc.update("INSERT INTO " + table("tb_follow") + "(user_id,follow_user_id) VALUES(2,1),(3,1)");
        inbox = new FeedRedisRepository(redis, "hmdp:test:feed:" + UUID.randomUUID() + ":");
        ReflectionTestUtils.setField(blogs, "inbox", inbox);
        ReflectionTestUtils.setField(blogs, "delivery", delivery(inbox));
        login(1);
    }

    @AfterEach
    void clear() {
        UserHolder.removeUser();
        if (inbox != null) redis.delete(java.util.stream.LongStream.rangeClosed(1, 10).mapToObj(inbox::key).toList());
    }

    @AfterAll
    void close() {
        try {
            if (jdbc != null) {
                for (String name : tables.keySet()) jdbc.execute("DROP TABLE IF EXISTS " + table(name));
                before.forEach((name, count) -> assertEquals(count, jdbc.queryForObject("SELECT COUNT(*) FROM " + name, Long.class)));
            }
        } finally { if (redisFactory != null) redisFactory.destroy(); }
    }

    @Test
    void committedPublicationSnapshotsFansPushesOnlyToThemAndUsesOneTimestamp() {
        Blog blog = note().setId(999L).setUserId(999L);
        Result result = blogs.saveBlog(blog);
        assertTrue(result.getSuccess());
        assertEquals(blog.getId(), result.getData());
        assertEquals(1L, jdbc.queryForObject("SELECT user_id FROM " + table("tb_blog"), Long.class));
        assertEquals(2L, countTasks("SUCCESS"));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM " + table("tb_feed_delivery_task")
                + " WHERE next_attempt_at<>published_at OR next_attempt_at=0", Long.class));
        double score = redis.opsForZSet().score(inbox.key(2), blog.getId().toString());
        assertEquals(score, redis.opsForZSet().score(inbox.key(3), blog.getId().toString()));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(inbox.key(4))));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(inbox.key(1))));
        inbox.push(2, blog.getId(), (long) score + 99999);
        assertEquals(score, redis.opsForZSet().score(inbox.key(2), blog.getId().toString()));
    }

    @Test
    void dueRetriesAreNotStarvedByContinuouslyCreatedTasks() {
        jdbc.update("DELETE FROM " + table("tb_follow") + " WHERE user_id=3");
        long publishedAt = System.currentTimeMillis() - 10000;
        for (int index = 0; index < 3; index++) {
            long blogId = 100 + index;
            insertNote(blogId);
            assertEquals(1, tasks.createForFollowers(blogId, 1, publishedAt));
            FeedDeliveryTask task = tasks.selectNewForBlog(blogId, 0, 1).get(0);
            assertEquals(1, tasks.claim(task.getId(), 0, 5, "failed-first-attempt",
                    publishedAt, publishedAt + 60000));
            assertEquals(1, tasks.fail(task.getId(), "failed-first-attempt", "PENDING",
                    publishedAt + 1000 + index, "temporary failure"));
        }

        FeedDeliveryService worker = delivery(inbox);
        ReflectionTestUtils.setField(worker, "batchSize", 1);
        // 每轮新增任务，且每轮只能领取一条，检验真实 SQL 是否让旧重试一直排不到。
        for (int round = 0; round < 3; round++) {
            long newBlogId = 200 + round;
            insertNote(newBlogId);
            assertEquals(1, tasks.createForFollowers(newBlogId, 1, publishedAt + 5000 + round));
            worker.retry();
            assertEquals("SUCCESS", jdbc.queryForObject("SELECT status FROM " + table("tb_feed_delivery_task")
                    + " WHERE blog_id=?", String.class, 100L + round),
                    "较晚创建的新任务不能抢占更早到期的重试任务");
            assertEquals(round + 1L, countTasks("SUCCESS"));
            assertNull(redis.opsForZSet().score(inbox.key(2), Long.toString(newBlogId)));
        }
        assertEquals(3L, countTasks("PENDING"));
        worker.retry();
        assertNotNull(redis.opsForZSet().score(inbox.key(2), "200"), "旧重试处理后继续处理新任务");
    }

    @Test
    void initialDueTimeBackfillOnlyChangesLegacyUnattemptedPendingTasksAndIsIdempotent() throws Exception {
        String[] statuses = {"PENDING", "PENDING", "PENDING", "PROCESSING", "SUCCESS", "FAILED", "PENDING"};
        int[] attempts = {0, 1, 0, 1, 1, 5, 0};
        long[] dueTimes = {0, 0, 1200, 0, 0, 0, 0};
        for (int index = 0; index < statuses.length; index++) {
            long blogId = 300 + index;
            insertNote(blogId);
            jdbc.update("INSERT INTO " + table("tb_feed_delivery_task")
                            + "(blog_id,user_id,published_at,status,attempts,next_attempt_at,lease_until,claim_token)"
                            + " VALUES(?,2,?,?,?,?,?,?)", blogId, index == 6 ? 0 : 1000 + index,
                    statuses[index], attempts[index], dueTimes[index], index == 3 ? 60000 : 0,
                    index == 3 ? "active-owner" : null);
        }
        String stateQuery = "SELECT blog_id,user_id,published_at,status,attempts,lease_until,claim_token FROM "
                + table("tb_feed_delivery_task") + " ORDER BY blog_id";
        var originalStates = jdbc.queryForList(stateQuery);
        String migration = Files.readString(Path.of("sql/migrations/20261031_backfill_feed_initial_due_time.sql"))
                .replace("tb_feed_delivery_task", table("tb_feed_delivery_task"));

        assertEquals(1, jdbc.update(migration));
        assertEquals(List.of(1000L, 0L, 1200L, 0L, 0L, 0L, 0L), jdbc.queryForList(
                "SELECT next_attempt_at FROM " + table("tb_feed_delivery_task") + " ORDER BY blog_id", Long.class));
        assertEquals(originalStates, jdbc.queryForList(stateQuery), "不改变任务状态、尝试次数、原时间或租约");
        assertEquals(0, jdbc.update(migration), "重复执行回填无额外更新");
    }

    @Test
    void failedNoteInsertAndFailedTaskInsertRollBackWithoutAnyPush() {
        assertThrows(RuntimeException.class, () -> blogs.saveBlog(note().setTitle(null)));
        assertEquals(0L, rows("tb_blog"));
        assertEquals(0L, rows("tb_feed_delivery_task"));
        jdbc.execute("ALTER TABLE " + table("tb_feed_delivery_task") + " ADD CONSTRAINT chk_feed_test_reject CHECK (blog_id=0)");
        try {
            assertThrows(RuntimeException.class, () -> blogs.saveBlog(note()));
            assertEquals(0L, rows("tb_blog"), "创建任务失败必须回滚已经插入的笔记");
            assertEquals(0L, rows("tb_feed_delivery_task"));
            assertFalse(Boolean.TRUE.equals(redis.hasKey(inbox.key(2))));
        } finally {
            jdbc.execute("ALTER TABLE " + table("tb_feed_delivery_task") + " DROP CHECK chk_feed_test_reject");
        }
    }

    @Test
    void partialFailureSurvivesANewWorkerAndDoesNotRequeryTheFollowerSet() {
        FeedRedisRepository failing = spy(inbox);
        doThrow(new IllegalStateException("temporary Redis failure")).when(failing).push(eq(3L), anyLong(), anyLong());
        ReflectionTestUtils.setField(blogs, "delivery", delivery(failing));
        long id = ((Number) blogs.saveBlog(note()).getData()).longValue();
        assertEquals(1L, countTasks("SUCCESS"));
        assertEquals(1L, countTasks("PENDING"));
        long originalTime = jdbc.queryForObject("SELECT published_at FROM " + table("tb_feed_delivery_task") + " WHERE user_id=3", Long.class);
        jdbc.update("DELETE FROM " + table("tb_follow") + " WHERE user_id=3");
        jdbc.update("INSERT INTO " + table("tb_follow") + "(user_id,follow_user_id) VALUES(4,1)");
        jdbc.update("UPDATE " + table("tb_feed_delivery_task") + " SET next_attempt_at=0 WHERE status='PENDING'");
        delivery(inbox).retry(); // 新实例只依赖持久化任务，模拟进程状态丢失后的恢复。
        assertEquals(2L, countTasks("SUCCESS"));
        assertEquals((double) originalTime, redis.opsForZSet().score(inbox.key(3), Long.toString(id)));
        assertFalse(Boolean.TRUE.equals(redis.hasKey(inbox.key(4))));
    }

    @Test
    void redisSuccessFollowedByDatabaseAckFailureIsSafelyReplayed() {
        FeedDeliveryTaskMapper failingAck = spy(tasks);
        doThrow(new IllegalStateException("database ack unavailable")).when(failingAck).succeed(anyLong(), anyString());
        FeedDeliveryService worker = delivery(inbox);
        ReflectionTestUtils.setField(worker, "tasks", failingAck);
        ReflectionTestUtils.setField(blogs, "delivery", worker);
        long id = ((Number) blogs.saveBlog(note()).getData()).longValue();
        assertEquals(2L, countTasks("PENDING"));
        Double originalScore = redis.opsForZSet().score(inbox.key(2), Long.toString(id));
        assertNotNull(originalScore);
        jdbc.update("UPDATE " + table("tb_feed_delivery_task") + " SET next_attempt_at=0");
        delivery(inbox).retry();
        assertEquals(2L, countTasks("SUCCESS"));
        assertEquals(1L, redis.opsForZSet().size(inbox.key(2)));
        assertEquals(originalScore, redis.opsForZSet().score(inbox.key(2), Long.toString(id)));
    }

    @Test
    void leasesAreReclaimedAndOldOwnersCannotAcknowledgeNewClaims() throws Exception {
        publication.publish(note(), 1);
        var task = tasks.selectNewForBlog(jdbc.queryForObject("SELECT id FROM " + table("tb_blog"), Long.class), 0, 10).get(0);
        long now = System.currentTimeMillis();
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var first = executor.submit(() -> { start.await(); return tasks.claim(task.getId(), 0, 5, "old", now, now + 60000); });
            var second = executor.submit(() -> { start.await(); return tasks.claim(task.getId(), 0, 5, "other", now, now + 60000); });
            start.countDown();
            assertEquals(1, first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS));
            String owner = jdbc.queryForObject("SELECT claim_token FROM " + table("tb_feed_delivery_task") + " WHERE id=?", String.class, task.getId());
            jdbc.update("UPDATE " + table("tb_feed_delivery_task") + " SET lease_until=0 WHERE id=?", task.getId());
            assertEquals(1, tasks.claim(task.getId(), 1, 5, "new", now, now + 60000));
            assertEquals(0, tasks.succeed(task.getId(), owner));
            assertEquals(0, tasks.fail(task.getId(), owner, "FAILED", 0, "stale"));
            jdbc.update("UPDATE " + table("tb_feed_delivery_task") + " SET lease_until=0 WHERE id=?", task.getId());
            delivery(inbox).retry();
            assertEquals(2L, countTasks("SUCCESS"));
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)); }
    }

    @Test
    void exhaustedTasksAreRetainedAndExpiredFinalAttemptsDoNotGetStuck() {
        FeedRedisRepository failing = spy(inbox);
        doThrow(new IllegalStateException("unavailable")).when(failing).push(anyLong(), anyLong(), anyLong());
        FeedDeliveryService worker = delivery(failing);
        ReflectionTestUtils.setField(worker, "maxAttempts", 2);
        ReflectionTestUtils.setField(blogs, "delivery", worker);
        blogs.saveBlog(note());
        jdbc.update("UPDATE " + table("tb_feed_delivery_task") + " SET next_attempt_at=0");
        worker.retry();
        assertEquals(2L, countTasks("FAILED"));
        worker.retry();
        assertEquals(2, jdbc.queryForObject("SELECT MAX(attempts) FROM " + table("tb_feed_delivery_task"), Integer.class));
        jdbc.update("UPDATE " + table("tb_feed_delivery_task") + " SET status='PROCESSING',claim_token='crashed',lease_until=0");
        worker.retry();
        assertEquals(2L, countTasks("FAILED"));
    }

    @Test
    void equalTimestampsAcrossFourPagesHaveNoDuplicatesOrOmissions() {
        for (long id = 10; id <= 18; id++) { insertNote(id); inbox.push(2, id, id == 18 ? 900 : 1000); }
        login(2);
        long lastId = 2000;
        int offset = 0;
        List<Long> all = new ArrayList<>();
        List<Integer> offsets = new ArrayList<>();
        for (int page = 0; page < 6; page++) {
            ScrollResult result = page(lastId, offset);
            for (Object value : result.getList()) {
                Blog blog = (Blog) value;
                all.add(blog.getId());
                assertEquals("author-1", blog.getName());
                assertEquals(77, blog.getLiked());
                assertTrue(blog.getIsLike());
            }
            assertTrue(result.getList().size() <= 2);
            lastId = result.getMinTime(); offset = result.getOffset();
            offsets.add(offset);
        }
        assertEquals(List.of(17L,16L,15L,14L,13L,12L,11L,10L,18L), all);
        assertEquals(List.of(2,4,6,8,1,0), offsets);
        assertEquals(all.size(), new HashSet<>(all).size());
        assertEquals(0, lastId);
    }

    @Test
    void missingNotesAdvanceTheRawCursorAndEachUserOnlyReadsTheirOwnInbox() {
        insertNote(10); insertNote(11);
        inbox.push(2, 40, 1100); inbox.push(2, 41, 1100); inbox.push(2, 10, 1000);
        inbox.push(3, 11, 1200);
        login(2);
        ScrollResult missing = page(2000, 0);
        assertTrue(missing.getList().isEmpty());
        assertEquals(1100, missing.getMinTime());
        assertEquals(2, missing.getOffset());
        assertEquals(10L, ((Blog) page(missing.getMinTime(), missing.getOffset()).getList().get(0)).getId());
        login(3);
        assertEquals(11L, ((Blog) page(2000, 0).getList().get(0)).getId());
        login(4);
        ScrollResult empty = page(2000, 0);
        assertTrue(empty.getList().isEmpty()); assertEquals(0, empty.getMinTime()); assertEquals(0, empty.getOffset());
    }

    private Blog note() { return new Blog().setShopId(1L).setTitle("test").setImages("test.jpg").setContent("test"); }
    private void insertNote(long id) {
        jdbc.update("INSERT INTO " + table("tb_blog") + "(id,shop_id,user_id,title,images,content) VALUES(?,1,1,'test','test.jpg','test')", id);
    }
    private long rows(String name) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table(name), Long.class); }
    private long countTasks(String status) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table("tb_feed_delivery_task") + " WHERE status=?", Long.class, status); }
    private ScrollResult page(long lastId, int offset) { Result result = blogs.queryBlogOfFollow(lastId, offset); assertTrue(result.getSuccess()); return (ScrollResult) result.getData(); }
    private void login(long id) { UserDTO user = new UserDTO(); user.setId(id); UserHolder.saveUser(user); }
    private FeedDeliveryService delivery(FeedRedisRepository repository) {
        FeedDeliveryService worker = new FeedDeliveryService();
        ReflectionTestUtils.setField(worker, "tasks", tasks);
        ReflectionTestUtils.setField(worker, "inbox", repository);
        return worker;
    }
    private <T> T proxy(T target, Class<T> type) {
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return type.cast(proxy.getProxy());
    }
    private String table(String name) {
        String actual = tables.get(name);
        if (actual == null || !actual.matches("hmdp_test_feed_[a-z]+_[a-f0-9]{32}")) throw new IllegalArgumentException("Unexpected test table");
        return "`" + actual + "`";
    }
}
