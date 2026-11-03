package com.hmdp.service.impl;

import com.hmdp.config.StatisticsProperties;
import com.hmdp.dto.*;
import com.hmdp.repository.StatisticsRepository;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.hmdp.dto.UvDaily.Availability.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StatisticsServiceTest {
    private StatisticsRepository repository;
    private StatisticsProperties properties;
    private StatisticsService service;
    @BeforeEach void setup() {
        repository = mock(StatisticsRepository.class); properties = new StatisticsProperties();
        service = new StatisticsService(repository, properties, Clock.fixed(Instant.parse("2026-11-03T12:00:00Z"), ZoneOffset.UTC));
    }
    @Test void distinguishesNotCollectedExpiredAndAvailableZero() {
        assertEquals(new UvDaily("2026-11-02", null, true, NOT_COLLECTED), service.daily("2026-11-02").getData());
        verifyNoInteractions(repository); properties.setStartDate("2026-01-01");
        assertEquals(new UvDaily("2026-08-05", null, true, EXPIRED), service.daily("2026-08-05").getData());
        verifyNoInteractions(repository);
        assertEquals(new UvDaily("2026-11-03", 0L, true, AVAILABLE), service.daily(null).getData());
    }
    @Test void partialRecentCoverageIncludesSevenDatesWithoutInventedZeros() {
        when(repository.count(anyList())).thenReturn(2L);
        UvRecent result = (UvRecent) service.recent().getData();
        assertEquals(7, result.list().size()); assertEquals("2026-10-28", result.requestedFrom());
        assertEquals("2026-11-03", result.coverageFrom()); assertEquals("2026-11-03", result.coverageTo());
        assertFalse(result.complete()); assertEquals(2L, result.rangeUv());
        for (int i = 0; i < 6; i++) { assertEquals(NOT_COLLECTED, result.list().get(i).status()); assertNull(result.list().get(i).uv()); }
        verify(repository, times(2)).count(List.of(LocalDate.of(2026, 11, 3)));
    }
    @Test void fullCoverageUsesMultiKeyCountRatherThanAddingDailyCounts() {
        properties.setStartDate("2026-10-01");
        when(repository.count(anyList())).thenAnswer(invocation -> ((List<?>) invocation.getArgument(0)).size() == 1 ? 5L : 8L);
        UvRecent result = (UvRecent) service.recent().getData();
        assertTrue(result.complete()); assertEquals(8L, result.rangeUv());
        assertEquals(35L, result.list().stream().mapToLong(UvDaily::uv).sum());
        assertEquals("2026-10-28", result.coverageFrom());
    }
    @Test void unavailableRedisNeverReturnsZeroOrPartialResults() {
        when(repository.count(anyList())).thenThrow(new IllegalStateException("test"));
        assertFalse(service.daily(null).getSuccess()); assertNull(service.daily(null).getData());
        assertFalse(service.recent().getSuccess());
        doThrow(new IllegalStateException("test")).when(repository).record(any(), anyString());
        assertFalse(service.record(UUID.randomUUID().toString()).getSuccess());
    }
    @Test void strictDatesAndFutureDatesDoNotReachRedis() {
        for (String date : new String[]{"", " ", "2026-2-03", "2026-02-29", "2026-11-31", "0000-01-01", "2026-11-03 ", "2026-11-04", "2026-11-03T00:00:00", "２０２６-１１-０３"}) {
            assertFalse(service.daily(date).getSuccess(), date);
        }
        verifyNoInteractions(repository);
    }
    @Test void oneBusinessInstantDrivesTheEntireRecentWindowAcrossMidnight() {
        AtomicInteger reads = new AtomicInteger();
        Clock boundary = new Clock() {
            public ZoneId getZone() { return ZoneId.of("Asia/Shanghai"); }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return Instant.parse(reads.getAndIncrement() == 0 ? "2026-10-31T15:59:59.999Z" : "2026-10-31T16:00:00Z"); }
        };
        properties.setStartDate("2026-10-01"); StatisticsService crossing = new StatisticsService(repository, properties, boundary);
        UvRecent result = (UvRecent) crossing.recent().getData();
        assertEquals("2026-10-31", result.requestedTo()); assertEquals(1, reads.get());
        reads.set(0); crossing.record(UUID.randomUUID().toString());
        verify(repository).record(eq(LocalDate.of(2026, 10, 31)), anyString()); assertEquals(1, reads.get());
        reads.set(0); assertEquals("2026-10-31", ((UvDaily) crossing.daily(null).getData()).date()); assertEquals(1, reads.get());
    }
}
