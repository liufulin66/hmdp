package com.hmdp.service.impl;

import com.hmdp.mapper.BlogLikeMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BlogLikePersistenceServiceTest {
    @Test
    @SuppressWarnings("unchecked")
    void chunksSqlAndUsesPerUserVersionsEvenWhenGlobalVersionIsAlreadyAhead() {
        BlogLikeMapper mapper = mock(BlogLikeMapper.class);
        BlogLikePersistenceService service = new BlogLikePersistenceService();
        ReflectionTestUtils.setField(service, "blogLikeMapper", mapper);
        ReflectionTestUtils.setField(service, "sqlBatchSize", 2);
        when(mapper.lockVersion(1)).thenReturn(20L);
        when(mapper.selectStates(eq(1L), anyList())).thenAnswer(invocation -> {
            List<BlogLikeChange> requested = invocation.getArgument(1);
            assertTrue(requested.size() <= 2);
            return requested.stream().filter(change -> change.userId() <= 2)
                    .map(change -> change.userId() == 1 ? new BlogLikeChange(1, true, 8)
                            : new BlogLikeChange(2, true, 1)).toList();
        });
        doAnswer(invocation -> {
            List<BlogLikeChange> rows = invocation.getArgument(1);
            assertTrue(rows.size() <= 2);
            assertTrue(rows.stream().noneMatch(row -> row.userId() == 1), "旧的用户版本不能覆盖新状态");
            return null;
        }).when(mapper).upsertStates(eq(1L), anyList());
        when(mapper.incrementTotal(1, 0)).thenReturn(1);
        service.persist(new BlogLikeSnapshot(1, 99, 10, List.of(
                new BlogLikeChange(1, false, 7), new BlogLikeChange(2, false, 3),
                new BlogLikeChange(3, true, 9), new BlogLikeChange(4, false, 5),
                new BlogLikeChange(5, false, 6))));
        verify(mapper, times(3)).selectStates(eq(1L), anyList());
        verify(mapper, times(2)).upsertStates(eq(1L), anyList());
        verify(mapper).incrementTotal(1, 0);
        verify(mapper).updateVersion(1, 20);
    }
}
