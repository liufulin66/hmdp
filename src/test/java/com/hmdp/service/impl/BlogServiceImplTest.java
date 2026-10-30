package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BlogServiceImplTest {
    private BlogServiceImpl service;
    private IUserService userService;

    @BeforeEach
    void setUp() {
        UserHolder.removeUser();
        service = spy(new BlogServiceImpl());
        userService = mock(IUserService.class);
        ReflectionTestUtils.setField(service, "userService", userService);
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    @Test
    void anonymousReaderGetsContentAndAuthor() {
        Blog blog = new Blog().setId(1L).setUserId(2L).setContent("探店正文");
        doReturn(blog).when(service).getById(1L);
        when(userService.getById(2L)).thenReturn(new User().setId(2L)
                .setNickName("作者").setIcon("/imgs/author.png"));

        Result result = service.queryBlogById(1L);

        assertTrue(result.getSuccess());
        Blog detail = (Blog) result.getData();
        assertEquals("探店正文", detail.getContent());
        assertEquals("作者", detail.getName());
        assertEquals("/imgs/author.png", detail.getIcon());
        assertNull(UserHolder.getUser());
    }

    @Test
    void invalidIdsDoNotAccessStorage() {
        assertFalse(service.queryBlogById(null).getSuccess());
        assertFalse(service.queryBlogById(0L).getSuccess());
        assertFalse(service.queryBlogById(-1L).getSuccess());
        verify(service, never()).getById(any());
        verifyNoInteractions(userService);
    }

    @Test
    void missingBlogReturnsBusinessFailure() {
        doReturn(null).when(service).getById(1L);
        Result result = service.queryBlogById(1L);
        assertFalse(result.getSuccess());
        assertEquals("笔记不存在", result.getErrorMsg());
        verifyNoInteractions(userService);
    }

    @Test
    void missingAuthorDoesNotHideBlog() {
        Blog blog = new Blog().setId(1L).setUserId(2L).setContent("探店正文");
        doReturn(blog).when(service).getById(1L);
        Result result = service.queryBlogById(1L);
        assertTrue(result.getSuccess());
        assertSame(blog, result.getData());
        assertEquals("探店正文", blog.getContent());
        assertNull(blog.getName());
    }

    @Test
    void publicationUsesLoggedInAuthorAndReturnsGeneratedId() {
        UserDTO user = new UserDTO();
        user.setId(2L);
        UserHolder.saveUser(user);
        Blog blog = new Blog().setUserId(999L).setTitle("探店");
        doAnswer(invocation -> {
            assertEquals(2L, blog.getUserId());
            blog.setId(10L);
            return true;
        }).when(service).save(blog);

        Result result = service.saveBlog(blog);
        assertTrue(result.getSuccess());
        assertEquals(10L, result.getData());
    }
}
