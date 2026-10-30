package com.hmdp.config;

import com.hmdp.dto.Result;
import com.hmdp.dto.BlogLikeStatus;
import com.hmdp.controller.BlogController;
import com.hmdp.entity.Blog;
import com.hmdp.service.IBlogService;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

class AdminAccessTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(TestConfig.class);
        context.refresh();
        context.getBean(AdminProperties.class).setUserIds(Set.of(1L));
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @AfterEach
    void tearDown() { UserHolder.removeUser(); context.close(); }

    @Test
    void anonymousQueriesRemainPublicButManagementWritesRequireLogin() throws Exception {
        mvc.perform(get("/shop/1")).andExpect(status().isOk());
        mvc.perform(get("/voucher/list/1")).andExpect(status().isOk());
        mvc.perform(post("/shop")).andExpect(status().isUnauthorized());
        mvc.perform(put("/shop")).andExpect(status().isUnauthorized());
        mvc.perform(post("/voucher")).andExpect(status().isUnauthorized());
        mvc.perform(post("/voucher/seckill")).andExpect(status().isUnauthorized());
        mvc.perform(get("/voucher-order/1/result")).andExpect(status().isUnauthorized());
    }

    @Test
    void memberCannotWriteAndWhitelistedAdminCan() throws Exception {
        for (String path : new String[]{"/shop", "/voucher", "/voucher/seckill"}) {
            mvc.perform(post(path).header("authorization", "member")).andExpect(status().isForbidden());
            mvc.perform(post(path).header("authorization", "admin")).andExpect(status().isOk());
        }
        mvc.perform(put("/shop").header("authorization", "member")).andExpect(status().isForbidden());
        mvc.perform(put("/shop").header("authorization", "admin")).andExpect(status().isOk());
        mvc.perform(get("/voucher-order/1/result").header("authorization", "member")).andExpect(status().isOk());
    }

    @Test
    void emptyWhitelistDeniesAllManagementWrites() throws Exception {
        context.getBean(AdminProperties.class).setUserIds(Set.of());
        mvc.perform(post("/voucher").header("authorization", "admin")).andExpect(status().isForbidden());
    }

    @Test
    void anonymousReadersCanViewBlogDetailsAndHotList() throws Exception {
        IBlogService blogs = context.getBean(IBlogService.class);
        when(blogs.queryBlogById(1L)).thenReturn(Result.ok(
                new Blog().setId(1L).setContent("探店正文").setName("作者")));
        when(blogs.queryBlogById(999L)).thenReturn(Result.fail("笔记不存在"));
        when(blogs.queryHotBlog(1)).thenReturn(Result.ok(java.util.List.of()));

        mvc.perform(get("/blog/1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content").value("探店正文"))
                .andExpect(jsonPath("$.data.name").value("作者"));
        mvc.perform(get("/blog/999")).andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorMsg").value("笔记不存在"));
        mvc.perform(get("/blog/hot")).andExpect(status().isOk());
    }

    @Test
    void anonymousReadersCannotPublishLikeOrReadPersonalBlogs() throws Exception {
        mvc.perform(post("/blog").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/blog/like/1")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/blog/like/1")).andExpect(status().isUnauthorized());
        mvc.perform(get("/blog/of/me")).andExpect(status().isUnauthorized());
        verifyNoInteractions(context.getBean(IBlogService.class));
    }

    @Test
    void loggedInMembersCanStillUsePersonalBlogRoutes() throws Exception {
        IBlogService blogs = context.getBean(IBlogService.class);
        when(blogs.saveBlog(any(Blog.class))).thenReturn(Result.ok(10L));
        when(blogs.likeBlog(1L)).thenReturn(Result.ok(new BlogLikeStatus(11, true)));
        when(blogs.unlikeBlog(1L)).thenReturn(Result.ok(new BlogLikeStatus(10, false)));
        when(blogs.queryMyBlog(1)).thenReturn(Result.ok(java.util.List.of()));

        mvc.perform(post("/blog").header("authorization", "member")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(10));
        mvc.perform(put("/blog/like/1").header("authorization", "member"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.liked").value(11))
                .andExpect(jsonPath("$.data.isLike").value(true));
        mvc.perform(delete("/blog/like/1").header("authorization", "member"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.liked").value(10))
                .andExpect(jsonPath("$.data.isLike").value(false));
        mvc.perform(get("/blog/of/me").header("authorization", "member"))
                .andExpect(status().isOk());
        verify(blogs).likeBlog(1L);
        verify(blogs).unlikeBlog(1L);
        verify(blogs).queryMyBlog(1);
    }

    @Configuration
    @EnableWebMvc
    @Import(MvcConfig.class)
    static class TestConfig {
        @Bean
        @SuppressWarnings("unchecked")
        StringRedisTemplate stringRedisTemplate() {
            StringRedisTemplate redis = mock(StringRedisTemplate.class);
            HashOperations<String, Object, Object> hash = mock(HashOperations.class);
            when(redis.opsForHash()).thenReturn(hash);
            when(hash.entries(anyString())).thenAnswer(invocation -> {
                String key = invocation.getArgument(0);
                if (key.endsWith(":admin")) return Map.of("id", "1", "nickName", "admin");
                if (key.endsWith(":member")) return Map.of("id", "2", "nickName", "member");
                return Map.of();
            });
            return redis;
        }

        @Bean
        Routes routes() { return new Routes(); }

        @Bean
        IBlogService blogService() { return mock(IBlogService.class); }

        @Bean
        BlogController blogController() { return new BlogController(); }
    }

    @RestController
    static class Routes {
        @GetMapping({"/shop/1", "/voucher/list/1", "/voucher-order/1/result"})
        Result read() { return Result.ok(); }
        @PostMapping({"/shop", "/voucher", "/voucher/seckill"})
        Result create() { return Result.ok(); }
        @PutMapping("/shop")
        Result update() { return Result.ok(); }
    }
}
