package com.hmdp.service.impl;

import com.hmdp.repository.BlogLikeRedisRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.*;

class BlogLikeFlushServiceTest {
    private BlogLikeFlushService service;
    private BlogLikeRedisRepository redis;
    private BlogLikePersistenceService persistence;
    private RLock lock;
    private BlogLikeSnapshot snapshot;

    @BeforeEach
    void setUp() {
        service = new BlogLikeFlushService();
        redis = mock(BlogLikeRedisRepository.class);
        persistence = mock(BlogLikePersistenceService.class);
        RedissonClient redisson = mock(RedissonClient.class);
        lock = mock(RLock.class);
        ReflectionTestUtils.setField(service, "likes", redis);
        ReflectionTestUtils.setField(service, "persistence", persistence);
        ReflectionTestUtils.setField(service, "redisson", redisson);
        when(redis.dirtyIds(100)).thenReturn(Set.of("1"));
        when(redis.lockKey(1)).thenReturn("lock:1");
        when(redisson.getLock("lock:1")).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        snapshot = new BlogLikeSnapshot(1, 11, 1, List.of(new BlogLikeChange(2, true, 1)));
        when(redis.snapshot(1, 500)).thenReturn(snapshot);
    }

    @Test
    void acknowledgesOnlyAfterPersistenceReturnsAndReleasesLock() {
        service.flush();
        InOrder order = inOrder(persistence, redis);
        order.verify(redis).dirtyIds(100);
        order.verify(redis).lockKey(1);
        order.verify(redis).snapshot(1, 500);
        order.verify(persistence).persist(snapshot);
        order.verify(redis).acknowledge(snapshot);
        verify(lock).unlock();
    }

    @Test
    void failedTransactionRetainsPendingChangesForRetry() {
        doThrow(new IllegalStateException("database unavailable")).when(persistence).persist(snapshot);
        service.flush();
        verify(redis, never()).acknowledge(any(BlogLikeSnapshot.class));
        verify(redis).retryLater(1);
        verify(lock).unlock();
    }

    @Test
    void anotherInstanceHoldingLockPreventsConcurrentFlush() {
        when(lock.tryLock()).thenReturn(false);
        service.flush();
        verifyNoInteractions(persistence);
        verify(redis, never()).snapshot(anyLong(), anyInt());
        verify(redis).retryLater(1);
        verify(lock, never()).unlock();
    }

    @Test
    void legacyIndexBuildingYieldsTheSlotWithoutPersistingAnIncompleteSnapshot() {
        when(redis.snapshot(1, 500)).thenReturn(null);
        service.flush();
        verifyNoInteractions(persistence);
        verify(redis).retryLater(1);
        verify(redis, never()).acknowledge(any(BlogLikeSnapshot.class));
        verify(lock).unlock();
    }
}
