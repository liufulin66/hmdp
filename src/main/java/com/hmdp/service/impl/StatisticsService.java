package com.hmdp.service.impl;

import com.hmdp.config.StatisticsProperties;
import com.hmdp.dto.*;
import com.hmdp.repository.StatisticsRepository;
import com.hmdp.utils.StatisticsPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static com.hmdp.config.BusinessTimeConfig.BUSINESS_ZONE;
import static com.hmdp.dto.UvDaily.Availability.*;

@Slf4j
@Service
public class StatisticsService {
    private final StatisticsRepository repository;
    private final StatisticsProperties properties;
    private final Clock clock;
    private final AtomicLong nextWarning = new AtomicLong();
    public StatisticsService(StatisticsRepository repository, StatisticsProperties properties, @Qualifier("businessClock") Clock clock) {
        this.repository = repository; this.properties = properties; this.clock = clock.withZone(BUSINESS_ZONE);
    }

    public Result record(String visitor) {
        LocalDate today = LocalDate.now(clock);
        if (today.isBefore(properties.collectionStart())) return Result.fail("UV采集尚未启用");
        try { repository.record(today, visitor); return Result.ok(Map.of("collected", true)); }
        catch (RuntimeException e) { warn(e); return Result.fail("浏览事件暂未采集"); }
    }

    public Result daily(String date) {
        LocalDate today = LocalDate.now(clock), selected;
        try { selected = date == null ? today : StatisticsPolicy.date(date); }
        catch (RuntimeException e) { return Result.fail("日期必须为有效的yyyy-MM-dd格式"); }
        if (selected.isAfter(today)) return Result.fail("不能查询未来日期");
        try { return Result.ok(readDay(selected, today)); }
        catch (RuntimeException e) { warn(e); return Result.fail("UV统计暂不可用，请重试"); }
    }

    public Result recent() {
        LocalDate today = LocalDate.now(clock), from = today.minusDays(6);
        try {
            List<UvDaily> list = new ArrayList<>(); List<LocalDate> available = new ArrayList<>();
            for (int i = 0; i < StatisticsPolicy.RECENT_DAYS; i++) {
                LocalDate date = from.plusDays(i); UvDaily day = readDay(date, today); list.add(day);
                if (day.status() == AVAILABLE) available.add(date);
            }
            Long union = available.isEmpty() ? null : repository.count(available);
            return Result.ok(new UvRecent(List.copyOf(list), from.toString(), today.toString(),
                    available.isEmpty() ? null : available.get(0).toString(),
                    available.isEmpty() ? null : available.get(available.size() - 1).toString(), union,
                    available.size() == StatisticsPolicy.RECENT_DAYS, true));
        } catch (RuntimeException e) { warn(e); return Result.fail("UV统计暂不可用，请重试"); }
    }

    private UvDaily readDay(LocalDate date, LocalDate today) {
        if (date.isBefore(properties.collectionStart())) return new UvDaily(date.toString(), null, true, NOT_COLLECTED);
        if (date.isBefore(today.minusDays(StatisticsPolicy.RETENTION_DAYS - 1))) return new UvDaily(date.toString(), null, true, EXPIRED);
        return new UvDaily(date.toString(), repository.count(List.of(date)), true, AVAILABLE);
    }

    private void warn(RuntimeException error) {
        long now = System.currentTimeMillis(), next = nextWarning.get();
        if (now >= next && nextWarning.compareAndSet(next, now + 60_000)) {
            log.warn("UV统计暂不可用，错误类型={}；同实例日志每分钟最多一条", error.getClass().getSimpleName());
        }
    }
}
