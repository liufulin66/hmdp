package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.DynamicTableNameInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.mapper.UserMapper;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** 多连接共享随机命名的物理测试表；不启动应用，不写业务表或 Redis。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "hmdp.integration", matches = "true")
class FollowIntegrationTest {
    private JdbcTemplate jdbc;
    private FollowServiceImpl service;
    private String followTable;
    private String userTable;
    private long businessRowsBefore;

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
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                env.getRequiredProperty("spring.datasource.url"), env.getRequiredProperty("spring.datasource.username"),
                env.getRequiredProperty("spring.datasource.password"));
        jdbc = new JdbcTemplate(dataSource);
        assertEquals("hmdp", jdbc.queryForObject("SELECT DATABASE()", String.class));
        businessRowsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM tb_follow", Long.class);
        String suffix = UUID.randomUUID().toString().replace("-", "");
        followTable = "hmdp_test_follow_" + suffix;
        userTable = "hmdp_test_user_" + suffix;
        // 只复制结构（包括真实表索引），不复制任何用户或关注数据。
        jdbc.execute("CREATE TABLE " + table(followTable) + " LIKE tb_follow");
        jdbc.execute("CREATE TABLE " + table(userTable) + " LIKE tb_user");

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(FollowMapper.class);
        configuration.addMapper(UserMapper.class);
        DynamicTableNameInnerInterceptor tableNames = new DynamicTableNameInnerInterceptor();
        tableNames.setTableNameHandler((sql, name) -> switch (name) {
            case "tb_follow" -> followTable;
            case "tb_user" -> userTable;
            default -> name;
        });
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(tableNames);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        factory.setPlugins(interceptor);
        SqlSessionTemplate session = new SqlSessionTemplate(factory.getObject());
        FollowMapper mapper = session.getMapper(FollowMapper.class);
        assertEquals(1, mapper.isRelationUniqueIndex("uk_follow_user_target"), "请先执行关注唯一索引增量迁移");
        UserServiceImpl users = new UserServiceImpl();
        ReflectionTestUtils.setField(users, "baseMapper", session.getMapper(UserMapper.class));
        service = new FollowServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "userService", users);
    }

    @BeforeEach
    void fixtures() {
        jdbc.update("DELETE FROM " + table(followTable));
        jdbc.update("DELETE FROM " + table(userTable));
        for (long id = 1; id <= 20; id++) {
            jdbc.update("INSERT INTO " + table(userTable) + "(id,phone,nick_name,icon) VALUES(?,?,?,?)",
                    id, "test-" + id, "user-" + id, "icon-" + id);
        }
        login(1);
    }

    @AfterEach
    void clearIdentity() { UserHolder.removeUser(); }

    @AfterAll
    void clean() {
        if (jdbc == null) return;
        try {
            if (followTable != null) jdbc.execute("DROP TABLE IF EXISTS " + table(followTable));
        } finally {
            if (userTable != null) jdbc.execute("DROP TABLE IF EXISTS " + table(userTable));
        }
        assertEquals(businessRowsBefore, jdbc.queryForObject("SELECT COUNT(*) FROM tb_follow", Long.class));
    }

    @Test
    void followUnfollowAndRepeatedRequestsAreSynchronousAndIdempotent() {
        assertEquals(false, service.isFollow(2L).getData());
        assertEquals(true, service.follow(2L, true).getData());
        assertEquals(true, service.isFollow(2L).getData());
        assertEquals(true, service.follow(2L, true).getData());
        assertEquals(1, rows());
        assertEquals(false, service.follow(2L, false).getData());
        assertEquals(false, service.follow(2L, false).getData());
        assertEquals(false, service.isFollow(2L).getData());
        assertEquals(0, rows());
    }

    @Test
    void concurrentFollowsUseTheRealUniqueConstraintAcrossConnections() throws Exception {
        int count = 16;
        ExecutorService executor = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Result>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                futures.add(executor.submit(() -> {
                    login(1);
                    try {
                        ready.countDown();
                        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                        return service.follow(2L, true);
                    } finally { UserHolder.removeUser(); }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            for (Future<Result> future : futures) {
                Result result = future.get(20, TimeUnit.SECONDS);
                assertTrue(result.getSuccess());
                assertEquals(true, result.getData());
            }
            assertEquals(1, rows());
            // 绕过业务逻辑直接插入仍会被拒绝，证明不是 Mock 或先查后插的效果。
            assertThrows(DuplicateKeyException.class, () -> insert(1, 2));
            assertEquals(1, rows());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void usersAreIsolatedAndDeleteRequiresBothColumns() {
        service.follow(3L, true);
        login(2);
        service.follow(3L, true);
        assertEquals(2, rows());
        service.follow(3L, false);
        assertEquals(false, service.isFollow(3L).getData());
        login(1);
        assertEquals(true, service.isFollow(3L).getData());
        assertEquals(1, rows());
    }

    @Test
    void commonFollowIsTheOutgoingIntersectionInAscendingBoundedPages() {
        for (int id = 9; id >= 3; id--) {
            insert(1, id);
            insert(2, id);
        }
        insert(1, 10);
        insert(2, 11);
        insert(1, 2);
        insert(2, 1);
        assertEquals(List.of(3L, 4L, 5L, 6L, 7L), commonIds(1));
        assertEquals(List.of(8L, 9L), commonIds(2));
        assertEquals(List.of(), commonIds(3));
        assertEquals(List.of(), commonIds(1000));
    }

    @Test
    void emptyResultsAndValidationDoNotCreateRelations() {
        assertEquals(List.of(), commonIds(1));
        assertFalse(service.follow(999L, true).getSuccess());
        assertFalse(service.isFollow(999L).getSuccess());
        assertFalse(service.commonFollow(999L, 1).getSuccess());
        assertFalse(service.follow(1L, true).getSuccess());
        assertFalse(service.follow(1L, false).getSuccess());
        assertEquals(false, service.isFollow(1L).getData());
        assertFalse(service.commonFollow(1L, 1).getSuccess());
        assertFalse(service.follow(-1L, true).getSuccess());
        assertFalse(service.commonFollow(2L, 1001).getSuccess());
        UserHolder.removeUser();
        assertFalse(service.follow(2L, true).getSuccess());
        assertEquals(0, rows());
    }

    @Test
    void anotherDatabaseUniqueConstraintIsNeverTreatedAsAnIdempotentFollow() {
        jdbc.execute("ALTER TABLE " + table(followTable) + " ADD UNIQUE KEY uk_follow_test_target (follow_user_id)");
        try {
            service.follow(3L, true);
            login(2);
            assertThrows(DuplicateKeyException.class, () -> service.follow(3L, true));
            assertEquals(false, service.isFollow(3L).getData());
            assertEquals(1, rows());
        } finally {
            jdbc.execute("ALTER TABLE " + table(followTable) + " DROP INDEX uk_follow_test_target");
        }
    }

    private List<Long> commonIds(int current) {
        Result result = service.commonFollow(2L, current);
        assertTrue(result.getSuccess());
        @SuppressWarnings("unchecked") List<UserDTO> users = (List<UserDTO>) result.getData();
        assertTrue(users.size() <= 5);
        return users.stream().map(UserDTO::getId).toList();
    }

    private void insert(long userId, long followUserId) {
        jdbc.update("INSERT INTO " + table(followTable) + "(user_id,follow_user_id) VALUES(?,?)", userId, followUserId);
    }

    private long rows() { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table(followTable), Long.class); }

    private void login(long id) {
        UserDTO user = new UserDTO();
        user.setId(id);
        UserHolder.saveUser(user);
    }

    private String table(String name) {
        if (!name.matches("hmdp_test_(follow|user)_[a-f0-9]{32}")) throw new IllegalArgumentException("Unexpected test table");
        return "`" + name + "`";
    }
}
