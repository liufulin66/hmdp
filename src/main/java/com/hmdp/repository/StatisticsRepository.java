package com.hmdp.repository;

import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.StatisticsPolicy;
import com.hmdp.utils.VisitorCookie;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import static com.hmdp.config.BusinessTimeConfig.BUSINESS_ZONE;

@Repository
public class StatisticsRepository {
    private static final DefaultRedisScript<Long> RECORD = new DefaultRedisScript<>();
    static { RECORD.setLocation(new ClassPathResource("site-uv-record.lua")); RECORD.setResultType(Long.class); }
    private final StringRedisTemplate redis;
    private final String prefix;
    // 仅隔离验收时覆盖此前缀；正常部署始终使用 uv:site:。
    @Autowired public StatisticsRepository(StringRedisTemplate redis,
            @Value("${hmdp.statistics.key-prefix:" + RedisConstants.SITE_UV_KEY + "}") String prefix) {
        this.redis = redis; this.prefix = prefix;
    }

    public String key(LocalDate date) { return prefix + date.format(DateTimeFormatter.BASIC_ISO_DATE); }
    public void record(LocalDate date, String visitor) {
        if (!VisitorCookie.valid(visitor)) throw new IllegalArgumentException("访客标识无效");
        long expiresAt = date.plusDays(StatisticsPolicy.RETENTION_DAYS).atStartOfDay(BUSINESS_ZONE).toEpochSecond();
        Long result = redis.execute(RECORD, List.of(key(date)), visitor.toLowerCase(Locale.ROOT), Long.toString(expiresAt));
        if (!Long.valueOf(1).equals(result)) throw new IllegalStateException("UV采集结果不明确");
    }
    public long count(List<LocalDate> dates) {
        if (dates == null || dates.isEmpty() || dates.size() > StatisticsPolicy.RECENT_DAYS) throw new IllegalArgumentException("UV查询范围无效");
        String[] keys = dates.stream().map(this::key).toArray(String[]::new);
        Long result = redis.opsForHyperLogLog().size(keys);
        if (result == null || result < 0) throw new IllegalStateException("UV读取结果不明确");
        return result;
    }
}
