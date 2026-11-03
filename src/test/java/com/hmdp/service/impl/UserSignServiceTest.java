package com.hmdp.service.impl;

import com.hmdp.dto.*;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserSignServiceTest {
    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private UserSignService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        redis = mock(StringRedisTemplate.class); values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        service = new UserSignService(redis, Clock.fixed(Instant.parse("2026-11-03T00:00:00Z"), ZoneOffset.UTC));
        user(1001);
    }

    @AfterEach void cleanup() { UserHolder.removeUser(); }
    private void user(long id) { UserDTO user = new UserDTO(); user.setId(id); UserHolder.saveUser(user); }

    @Test void setBitOldValueAloneDeterminesFirstSign() {
        when(values.setBit("sign:1001:202611", 2, true)).thenReturn(false, true);
        assertEquals(new SignResult("2026-11-03", true, true), service.sign().getData());
        assertEquals(new SignResult("2026-11-03", true, false), service.sign().getData());
        verify(values, times(2)).setBit("sign:1001:202611", 2, true);
        verifyNoMoreInteractions(values);
        verify(redis, never()).expire(anyString(), any(Duration.class));
    }

    @Test void readsOnlyCurrentUserAndServerDate() {
        when(values.bitField(eq("sign:1001:202611"), any(BitFieldSubCommands.class))).thenReturn(List.of(0L), List.of(1L));
        assertEquals(new SignStatus("2026-11-03", false, 0), service.status().getData());
        assertEquals(new SignStatus("2026-11-03", true, 1), service.status().getData());
        verify(values, times(2)).bitField(eq("sign:1001:202611"), any(BitFieldSubCommands.class));
        verify(values, never()).getBit(anyString(), anyLong());
    }

    @Test void monthIsStrictAndFutureMonthsNeverReachRedis() {
        for (String month : new String[]{"", " ", "2026-1", "2026-00", "2026-13", "2026-11-01", "2026-11 ",
                "26-11", "0000-01", "+2026-11", "2026-12", "2027-01", "２０２６-１１"}) {
            assertFalse(service.calendar(month).getSuccess(), month);
        }
        verifyNoInteractions(values);
    }

    @Test void missingMonthUsesTodayAndOneReadGeneratesBothFields() {
        when(values.bitField(eq("sign:1001:202611"), any(BitFieldSubCommands.class)))
                .thenReturn(List.of((1L << 29) | (1L << 27) | 1L));
        assertEquals(new SignCalendar("2026-11", 30, List.of(1, 3, 30), 3), service.calendar(null).getData());
        verify(values).bitField(eq("sign:1001:202611"), any(BitFieldSubCommands.class));
        verifyNoMoreInteractions(values);
    }

    @Test void redisFailuresAndIndeterminateRepliesAreNeverSuccessful() {
        when(values.setBit(anyString(), anyLong(), eq(true))).thenReturn(null);
        assertFalse(service.sign().getSuccess());
        when(values.setBit(anyString(), anyLong(), eq(true))).thenThrow(new DataAccessResourceFailureException("test"));
        assertFalse(service.sign().getSuccess());
        for (List<Long> reply : Arrays.asList(null, List.<Long>of(), Arrays.asList((Long) null), List.of(-1L), List.of(0L, 1L), List.of(1L << 31))) {
            when(values.bitField(anyString(), any(BitFieldSubCommands.class))).thenReturn(reply);
            assertFalse(service.status().getSuccess()); assertFalse(service.calendar(null).getSuccess());
        }
        when(values.bitField(anyString(), any(BitFieldSubCommands.class))).thenThrow(new DataAccessResourceFailureException("test"));
        assertFalse(service.status().getSuccess()); assertFalse(service.calendar(null).getSuccess());
    }

    @Test void anonymousServiceCallsCannotAccessRedis() {
        UserHolder.removeUser();
        assertFalse(service.sign().getSuccess()); assertFalse(service.status().getSuccess());
        assertFalse(service.calendar(null).getSuccess()); verifyNoInteractions(values);
    }

    @Test void oneInstantIsCapturedEvenWhenClockCrossesShanghaiMidnight() {
        AtomicInteger reads = new AtomicInteger();
        Clock ticking = new Clock() {
            public ZoneId getZone() { return ZoneId.of("Asia/Shanghai"); }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return Instant.parse(reads.getAndIncrement() == 0
                    ? "2026-10-31T15:59:59.999999999Z" : "2026-10-31T16:00:00Z"); }
        };
        UserSignService boundary = new UserSignService(redis, ticking);
        when(values.setBit("sign:1001:202610", 30, true)).thenReturn(false);
        assertEquals(new SignResult("2026-10-31", true, true), boundary.sign().getData());
        assertEquals(1, reads.get());
        reads.set(0); when(values.bitField(eq("sign:1001:202610"), any(BitFieldSubCommands.class))).thenReturn(List.of(0L));
        assertEquals(new SignStatus("2026-10-31", false, 0), boundary.status().getData()); assertEquals(1, reads.get());
        reads.set(0); when(values.bitField(eq("sign:1001:202610"), any(BitFieldSubCommands.class))).thenReturn(List.of(0L));
        assertEquals("2026-10", ((SignCalendar) boundary.calendar(null).getData()).month()); assertEquals(1, reads.get());
    }

    @Test void stopsAtFirstZeroAndOnlyReadsOneBitmapPerNeededMonth() {
        when(values.bitField(eq("sign:1001:202611"), any(BitFieldSubCommands.class))).thenReturn(List.of(7L));
        when(values.bitField(eq("sign:1001:202610"), any(BitFieldSubCommands.class))).thenReturn(List.of(7L));
        assertEquals(new SignStatus("2026-11-03", true, 6), service.status().getData());
        verify(values).bitField(eq("sign:1001:202611"), any(BitFieldSubCommands.class));
        verify(values).bitField(eq("sign:1001:202610"), any(BitFieldSubCommands.class));
        verifyNoMoreInteractions(values);
    }

    @Test void todayUnsignedStopsWithoutReadingHistory() {
        when(values.bitField(eq("sign:1001:202611"), any(BitFieldSubCommands.class))).thenReturn(List.of(6L));
        assertEquals(new SignStatus("2026-11-03", false, 0), service.status().getData());
        verify(values).bitField(eq("sign:1001:202611"), any(BitFieldSubCommands.class));
        verifyNoMoreInteractions(values);
    }

    @Test void failedOrAmbiguousHistoryNeverReturnsPartialStreak() {
        when(values.bitField(eq("sign:1001:202611"), any(BitFieldSubCommands.class))).thenReturn(List.of(7L));
        for (List<Long> reply : Arrays.asList(null, List.<Long>of(), Arrays.asList((Long) null), List.of(0L, 1L), List.of(-1L), List.of(1L << 31))) {
            when(values.bitField(eq("sign:1001:202610"), any(BitFieldSubCommands.class))).thenReturn(reply);
            Result result = service.status(); assertFalse(result.getSuccess()); assertNull(result.getData());
        }
        when(values.bitField(eq("sign:1001:202610"), any(BitFieldSubCommands.class)))
                .thenThrow(new DataAccessResourceFailureException("test history failure"));
        assertFalse(service.status().getSuccess());
        // 状态统计失败不影响原子写入接口自身的成功语义。
        when(values.setBit("sign:1001:202611", 2, true)).thenReturn(true);
        assertEquals(new SignResult("2026-11-03", true, false), service.sign().getData());
    }
}
