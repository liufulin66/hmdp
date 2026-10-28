package com.hmdp.utils;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class GlobalIdGeneratorTest {
    @Test
    void createsPositiveIncreasingIdsWithConfiguredNodeBits() {
        GlobalIdGenerator generator = new GlobalIdGenerator(7, 12);
        long previous = 0;
        for (int i = 0; i < 1000; i++) {
            long id = generator.nextId();
            assertTrue(id > previous);
            assertEquals(7L, (id >>> 12) & 31L);
            assertEquals(12L, (id >>> 17) & 31L);
            previous = id;
        }
    }

    @Test
    void supportsBoundaryNodeNumbers() {
        assertNodeBits(new GlobalIdGenerator(0, 0).nextId(), 0, 0);
        assertNodeBits(new GlobalIdGenerator(31, 31).nextId(), 31, 31);
    }

    @Test
    void rejectsInvalidNodeNumbers() {
        assertThrows(IllegalArgumentException.class, () -> new GlobalIdGenerator(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> new GlobalIdGenerator(32, 1));
        assertThrows(IllegalArgumentException.class, () -> new GlobalIdGenerator(1, -1));
        assertThrows(IllegalArgumentException.class, () -> new GlobalIdGenerator(1, 32));
    }

    @Test
    void differentNodesProduceDisjointIds() {
        GlobalIdGenerator first = new GlobalIdGenerator(1, 1);
        GlobalIdGenerator second = new GlobalIdGenerator(2, 1);
        GlobalIdGenerator third = new GlobalIdGenerator(1, 2);
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            assertTrue(ids.add(first.nextId()));
            assertTrue(ids.add(second.nextId()));
            assertTrue(ids.add(third.nextId()));
        }
    }

    @Test
    void explicitStringAndOrmCallsShareOneSequence() {
        GlobalIdGenerator generator = new GlobalIdGenerator(1, 1);
        long first = generator.nextId();
        long second = generator.nextId(new Object());
        String third = generator.nextIdString();
        assertTrue(second > first);
        assertTrue(Long.parseLong(third) > second);
        assertTrue(third.matches("[0-9]+"));
    }

    @Test
    void parallelCallsAcrossBothEntryPointsDoNotRepeat() throws Exception {
        GlobalIdGenerator generator = new GlobalIdGenerator(3, 4);
        int threads = 8;
        int perThread = 2000;
        var executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<Long>>> requests = new ArrayList<>();
        try {
            for (int thread = 0; thread < threads; thread++) {
                boolean ormEntry = thread % 2 == 0;
                requests.add(executor.submit(() -> {
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test timed out");
                    }
                    List<Long> result = new ArrayList<>(perThread);
                    for (int i = 0; i < perThread; i++) {
                        result.add(ormEntry ? generator.nextId(this) : generator.nextId());
                    }
                    return result;
                }));
            }
            start.countDown();
            Set<Long> unique = new HashSet<>();
            for (Future<List<Long>> request : requests) {
                unique.addAll(request.get(10, TimeUnit.SECONDS));
            }
            assertEquals(threads * perThread, unique.size());
            assertTrue(unique.stream().allMatch(id -> id > 0));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private void assertNodeBits(long id, long workerId, long datacenterId) {
        assertEquals(workerId, (id >>> 12) & 31L);
        assertEquals(datacenterId, (id >>> 17) & 31L);
    }
}
