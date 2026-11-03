package com.hmdp.service.impl;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.DynamicTableNameInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IUserService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 真实 MySQL 临时表：验证他人主页按目标用户分页、稳定排序和空列表。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "hmdp.integration", matches = "true")
class BlogOfUserIntegrationTest {
    private JdbcTemplate jdbc;
    private String table;
    private long before;
    private BlogServiceImpl service;
    private BlogMapper mapper;
    private IUserService users;
    private BlogLikeService likes;

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
        DriverManagerDataSource source = new DriverManagerDataSource(
                env.getRequiredProperty("spring.datasource.url"),
                env.getRequiredProperty("spring.datasource.username"),
                env.getRequiredProperty("spring.datasource.password"));
        jdbc = new JdbcTemplate(source);
        assertEquals("hmdp", jdbc.queryForObject("SELECT DATABASE()", String.class));
        before = jdbc.queryForObject("SELECT COUNT(*) FROM tb_blog", Long.class);
        table = "hmdp_test_blog_of_user_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE TABLE " + table + " LIKE tb_blog");

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(BlogMapper.class);
        DynamicTableNameInnerInterceptor names = new DynamicTableNameInnerInterceptor();
        names.setTableNameHandler((sql, name) -> "tb_blog".equals(name) ? table : name);
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(names);
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        factory.setPlugins(interceptor);
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(BlogMapper.class);

        users = mock(IUserService.class);
        likes = mock(BlogLikeService.class);
        service = new BlogServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        ReflectionTestUtils.setField(service, "userService", users);
        ReflectionTestUtils.setField(service, "blogLikeService", likes);
    }

    @BeforeEach
    void fixtures() {
        reset(users, likes);
        jdbc.update("DELETE FROM " + table);
        when(users.getById(2L)).thenReturn(new User().setId(2L).setNickName("two").setIcon("two.png"));
        when(users.getById(3L)).thenReturn(new User().setId(3L).setNickName("three").setIcon("three.png"));
        when(users.getById(4L)).thenReturn(new User().setId(4L).setNickName("four").setIcon("four.png"));
        for (int i = 0; i < 12; i++) {
            long id = 100 + i;
            // 每两条共享同一时间，验证 create_time DESC 后再按 id DESC 稳定排序。
            LocalDateTime time = LocalDateTime.of(2026, 11, 3, 10, 0, i / 2);
            jdbc.update("INSERT INTO " + table
                            + "(id,shop_id,user_id,title,images,content,liked,comments,create_time,update_time) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?)",
                    id, 1L, 2L, "note-" + id, "a.jpg", "content", 0, 0, time, time);
        }
        LocalDateTime newest = LocalDateTime.of(2026, 11, 3, 11, 0);
        jdbc.update("INSERT INTO " + table
                        + "(id,shop_id,user_id,title,images,content,liked,comments,create_time,update_time) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?)",
                200L, 1L, 3L, "other-user", "b.jpg", "content", 0, 0, newest, newest);
    }

    @AfterAll
    void close() {
        if (jdbc != null) {
            jdbc.execute("DROP TABLE IF EXISTS " + table);
            assertEquals(before, jdbc.queryForObject("SELECT COUNT(*) FROM tb_blog", Long.class));
        }
    }

    @Test
    void pagesOnlyTheTargetUsersNotesInStableTimeThenIdOrder() {
        Result first = service.queryBlogOfUser(2L, 1);
        assertTrue(first.getSuccess(), first.getErrorMsg());
        @SuppressWarnings("unchecked")
        List<Blog> pageOne = (List<Blog>) first.getData();
        assertEquals(10, pageOne.size());
        assertEquals(List.of(111L, 110L, 109L, 108L, 107L, 106L, 105L, 104L, 103L, 102L),
                pageOne.stream().map(Blog::getId).toList());
        assertTrue(pageOne.stream().allMatch(blog -> Objects.equals(2L, blog.getUserId())));
        assertTrue(pageOne.stream().allMatch(blog -> "two".equals(blog.getName()) && "two.png".equals(blog.getIcon())));
        verify(likes, times(10)).fillLikes(any(Blog.class));

        Result second = service.queryBlogOfUser(2L, 2);
        assertTrue(second.getSuccess(), second.getErrorMsg());
        @SuppressWarnings("unchecked")
        List<Blog> pageTwo = (List<Blog>) second.getData();
        assertEquals(List.of(101L, 100L), pageTwo.stream().map(Blog::getId).toList());
    }

    @Test
    void existingUserWithoutNotesReturnsAnEmptyArray() {
        Result result = service.queryBlogOfUser(4L, 1);
        assertTrue(result.getSuccess());
        assertEquals(List.of(), result.getData());
        verify(likes, never()).fillLikes(any(Blog.class));
    }

    @Test
    void invalidArgumentsAndMissingUsersReturnExplicitFailures() {
        assertFalse(service.queryBlogOfUser(null, 1).getSuccess());
        assertFalse(service.queryBlogOfUser(0L, 1).getSuccess());
        assertFalse(service.queryBlogOfUser(2L, 0).getSuccess());
        assertFalse(service.queryBlogOfUser(2L, 1001).getSuccess());
        Result missing = service.queryBlogOfUser(99L, 1);
        assertFalse(missing.getSuccess());
        assertEquals("用户不存在", missing.getErrorMsg());
        verify(likes, never()).fillLikes(any(Blog.class));
    }
}
