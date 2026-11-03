package com.hmdp.repository;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HyperLogLogOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StatisticsRepositoryTest {
    @Test @SuppressWarnings("unchecked") void nullOrNegativeCountsAreNotEmptySets() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        HyperLogLogOperations<String,String> operations = mock(HyperLogLogOperations.class);
        when(redis.opsForHyperLogLog()).thenReturn(operations);
        StatisticsRepository repository = new StatisticsRepository(redis, "test:");
        when(operations.size(any(String[].class))).thenReturn(null, -1L, 0L);
        List<LocalDate> dates = List.of(LocalDate.of(2026,11,3));
        assertThrows(IllegalStateException.class, () -> repository.count(dates));
        assertThrows(IllegalStateException.class, () -> repository.count(dates));
        assertEquals(0L, repository.count(dates));
    }
    @Test void anIndeterminateWriteIsNotSuccessful() {
        StatisticsRepository repository = new StatisticsRepository(mock(StringRedisTemplate.class), "test:");
        assertThrows(IllegalStateException.class, () -> repository.record(LocalDate.of(2026,11,3), UUID.randomUUID().toString()));
    }
    @Test void boundedQueriesAndInvalidIdentityNeverReachRedis() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class); StatisticsRepository repository = new StatisticsRepository(redis, "test:");
        assertThrows(IllegalArgumentException.class, () -> repository.count(List.of()));
        assertThrows(IllegalArgumentException.class, () -> repository.count(Collections.nCopies(8, LocalDate.now())));
        assertThrows(IllegalArgumentException.class, () -> repository.record(LocalDate.of(2026,11,3), "client chosen identity"));
        verifyNoInteractions(redis);
    }
}
