package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.repository.BlogLikeRedisRepository;
import com.hmdp.service.IUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BlogLikeRankingServiceTest {
    private BlogLikeRankingService service;
    private BlogLikeService core;
    private BlogLikeRedisRepository redis;
    private BlogLikePersistenceService persistence;
    private IUserService users;
    private RLock lock;

    @BeforeEach
    void setUp() throws Exception {
        service = new BlogLikeRankingService();
        core = mock(BlogLikeService.class);
        redis = mock(BlogLikeRedisRepository.class);
        persistence = mock(BlogLikePersistenceService.class);
        users = mock(IUserService.class);
        RedissonClient redisson = mock(RedissonClient.class);
        lock = mock(RLock.class);
        ReflectionTestUtils.setField(service, "blogLikeService", core);
        ReflectionTestUtils.setField(service, "likes", redis);
        ReflectionTestUtils.setField(service, "persistence", persistence);
        ReflectionTestUtils.setField(service, "userService", users);
        ReflectionTestUtils.setField(service, "redisson", redisson);
        when(core.ensureInitialized(1, null)).thenReturn(true);
        when(redis.lockKey(1)).thenReturn("lock:1");
        when(redisson.getLock("lock:1")).thenReturn(lock);
        when(lock.tryLock(2, TimeUnit.SECONDS)).thenReturn(true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void defaultTopThreeUsesOneBatchQueryAndKeepsRedisOrder() {
        when(redis.rankedUsers(1, 3)).thenReturn(List.of(9L, 2L, 7L));
        when(users.listByIds(List.of(9L, 2L, 7L))).thenReturn(List.of(
                new User().setId(7L).setNickName("C"), new User().setId(9L).setNickName("A"),
                new User().setId(2L).setNickName("B")));
        Result result = service.queryLikes(1L);
        assertTrue(result.getSuccess());
        assertEquals(List.of(9L, 2L, 7L), ((List<UserDTO>) result.getData()).stream().map(UserDTO::getId).toList());
        verify(users).listByIds(List.of(9L, 2L, 7L));
        verifyNoMoreInteractions(users);
        verifyNoInteractions(persistence);
    }

    @Test
    void emptyRankingDoesNotQueryUserProfiles() {
        when(redis.rankedUsers(1, 3)).thenReturn(List.of());
        assertEquals(List.of(), service.queryLikes(1L).getData());
        verifyNoInteractions(users, persistence);
    }

    @Test
    void invalidAndMissingBlogsNeverQueryRankOrUsers() {
        assertFalse(service.queryLikes(0L).getSuccess());
        assertFalse(service.queryLikes(null).getSuccess());
        assertFalse(service.queryLikes(2L).getSuccess());
        verifyNoInteractions(redis, users, persistence);
    }

    @Test
    void missingIndexIsInitializedBeforeRankingIsRead() {
        when(redis.rankedUsers(1, 3)).thenReturn(null, null, List.of());
        when(persistence.loadRankUsers(1)).thenReturn(List.of(new BlogLikeChange(9, true, 4)));
        assertTrue(service.queryLikes(1L).getSuccess());
        var order = inOrder(redis, persistence);
        order.verify(redis).rankedUsers(1, 3);
        order.verify(redis).lockKey(1);
        order.verify(redis).rankedUsers(1, 3);
        order.verify(redis).beginRanking(eq(1L), anyString());
        order.verify(persistence).loadRankUsers(1);
        order.verify(redis).seedRanking(eq(1L), anyString(), eq(List.of(new BlogLikeChange(9, true, 4))));
        order.verify(redis).seedPendingRanking(eq(1L), anyString());
        order.verify(redis).publishRanking(eq(1L), anyString());
        verify(lock).unlock();
        verify(redis, never()).abortRanking(anyLong(), anyString());
    }

    @Test
    void rebuildFailureAbortsOnlyTheDerivedIndexAndUnlocks() {
        when(redis.rankedUsers(1, 3)).thenReturn(null);
        when(persistence.loadRankUsers(1)).thenThrow(new IllegalStateException("database unavailable"));
        assertFalse(service.queryLikes(1L).getSuccess());
        verify(redis).abortRanking(eq(1L), anyString());
        verify(redis, never()).publishRanking(anyLong(), anyString());
        verify(redis, never()).change(anyLong(), anyLong(), anyBoolean());
        verify(lock).unlock();
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingUserProfileIsSkippedWithoutChangingTheRemainingOrder() {
        when(redis.rankedUsers(1, 3)).thenReturn(List.of(9L, 2L, 7L));
        when(users.listByIds(anyCollection())).thenReturn(List.of(new User().setId(7L), new User().setId(9L)));
        List<UserDTO> result = (List<UserDTO>) service.queryLikes(1L).getData();
        assertEquals(List.of(9L, 7L), result.stream().map(UserDTO::getId).toList());
    }
}
