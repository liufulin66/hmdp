package com.hmdp.service.impl;

import com.hmdp.dto.BlogLikeStatus;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.repository.BlogLikeRedisRepository;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.*;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BlogLikeServiceTest {
    private BlogLikeService service;
    private BlogLikeRedisRepository redis;
    private BlogLikePersistenceService persistence;

    @BeforeEach
    void setUp() {
        UserHolder.removeUser();
        service = new BlogLikeService();
        redis = mock(BlogLikeRedisRepository.class);
        persistence = mock(BlogLikePersistenceService.class);
        ReflectionTestUtils.setField(service, "likes", redis);
        ReflectionTestUtils.setField(service, "persistence", persistence);
        ReflectionTestUtils.setField(service, "redisson", mock(RedissonClient.class));
    }

    @AfterEach
    void tearDown() { UserHolder.removeUser(); }

    @Test
    void anonymousWritesAndInvalidIdsNeverTouchRedis() {
        assertFalse(service.changeLike(1L, true).getSuccess());
        login();
        assertFalse(service.changeLike(0L, true).getSuccess());
        assertFalse(service.changeLike(null, false).getSuccess());
        verifyNoInteractions(redis, persistence);
    }

    @Test
    void separateOperationsUseLoggedInUserAndReturnLiveState() {
        login();
        when(redis.read(1L, 2L)).thenReturn(new BlogLikeStatus(10, false));
        when(redis.change(1, 2, true)).thenReturn(new BlogLikeStatus(11, true));
        when(redis.change(1, 2, false)).thenReturn(new BlogLikeStatus(10, false));
        assertEquals(new BlogLikeStatus(11, true), service.changeLike(1L, true).getData());
        assertEquals(new BlogLikeStatus(10, false), service.changeLike(1L, false).getData());
        verifyNoInteractions(persistence);
    }

    @Test
    void redisWriteFailureDoesNotFallBackToUncoordinatedDatabaseWrites() {
        login();
        when(redis.read(1L, 2L)).thenThrow(new IllegalStateException("unavailable"));
        assertFalse(service.changeLike(1L, true).getSuccess());
        verifyNoInteractions(persistence);
    }

    @Test
    void queriesFallBackToCommittedDatabaseStateDuringRedisFailure() {
        when(redis.read(1L, null)).thenThrow(new IllegalStateException("unavailable"));
        when(persistence.readCommitted(1, null)).thenReturn(new BlogLikeStatus(10, false));
        Blog blog = new Blog().setId(1L);
        service.fillLikes(blog);
        assertEquals(10, blog.getLiked());
        assertFalse(blog.getIsLike());
    }

    private void login() {
        UserDTO user = new UserDTO();
        user.setId(2L);
        UserHolder.saveUser(user);
    }
}
