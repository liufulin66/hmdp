package com.hmdp.service.impl;

import com.hmdp.dto.*;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static com.hmdp.config.BusinessTimeConfig.BUSINESS_ZONE;
import static com.hmdp.utils.RedisConstants.USER_SIGN_KEY;

/** 每月一个 Bitmap；没有 TTL。SETBIT 的旧值是首次签到的唯一判断依据。 */
@Slf4j
@Service
public class UserSignService {
    private final StringRedisTemplate redis;
    private final Clock clock;
    private final String prefix;

    @Autowired
    public UserSignService(StringRedisTemplate redis, @Qualifier("signClock") Clock clock) {
        this(redis, clock, USER_SIGN_KEY);
    }

    // 集成测试使用随机命名空间，不访问业务签到 key。
    UserSignService(StringRedisTemplate redis, Clock clock, String prefix) {
        this.redis = redis;
        this.clock = clock.withZone(BUSINESS_ZONE);
        this.prefix = prefix;
    }

    public Result sign() {
        Long userId = currentUserId();
        if (userId == null) return Result.fail("请先登录");
        LocalDate today = LocalDate.now(clock);
        try {
            Boolean old = redis.opsForValue().setBit(key(userId, YearMonth.from(today)), today.getDayOfMonth() - 1, true);
            if (old == null) return Result.fail("签到结果尚未确认，请查询今日状态或重试");
            return Result.ok(new SignResult(today.toString(), true, !old));
        } catch (RuntimeException e) {
            log.warn("签到结果未确认，用户可查询状态或幂等重试，userId={}", userId, e);
            return Result.fail("签到结果尚未确认，请查询今日状态或重试");
        }
    }

    public Result status() {
        Long userId = currentUserId();
        if (userId == null) return Result.fail("请先登录");
        LocalDate today = LocalDate.now(clock);
        try {
            YearMonth month = YearMonth.from(today);
            int days = today.getDayOfMonth();
            long bitmap = readBitmap(userId, month, days);
            // GET uN 0 的最右位是本次读取的第 N 天，即今天。
            boolean signed = (bitmap & 1L) == 1;
            long continuousDays = 0;
            while (true) {
                int segment = 0;
                while (segment < days && (bitmap & 1L) == 1) {
                    segment++;
                    bitmap >>>= 1;
                }
                continuousDays = Math.addExact(continuousDays, segment);
                if (segment < days) return Result.ok(new SignStatus(today.toString(), signed, continuousDays));
                // 本段全部签满，才继续读前一个自然月；不截断或预加载所有历史。
                month = month.minusMonths(1);
                days = month.lengthOfMonth();
                bitmap = readBitmap(userId, month, days);
            }
        } catch (RuntimeException e) {
            log.warn("查询签到状态及连续统计失败，userId={}", userId, e);
            return Result.fail("签到状态及连续统计暂不可用，请重试");
        }
    }

    public Result calendar(String month) {
        Long userId = currentUserId();
        if (userId == null) return Result.fail("请先登录");
        LocalDate today = LocalDate.now(clock);
        YearMonth current = YearMonth.from(today), selected;
        if (month == null) selected = current;
        else {
            if (!month.matches("[0-9]{4}-(0[1-9]|1[0-2])") || month.startsWith("0000")) {
                return Result.fail("月份必须采用yyyy-MM格式且为有效月份");
            }
            selected = YearMonth.parse(month);
        }
        if (selected.isAfter(current)) return Result.fail("不能查询未来月份");
        int days = selected.lengthOfMonth();
        try {
            long bitmap = readBitmap(userId, selected, days);
            List<Integer> signedDays = new ArrayList<>();
            // Redis offset=0 是最高位；读取 uN 后，第 day 天位于 N-day。
            for (int day = 1; day <= days; day++) {
                if (((bitmap >>> (days - day)) & 1L) == 1) signedDays.add(day);
            }
            return Result.ok(new SignCalendar(selected.toString(), days, List.copyOf(signedDays), signedDays.size()));
        } catch (RuntimeException e) {
            log.warn("查询签到月历失败，userId={}, month={}", userId, selected, e);
            return Result.fail("签到月历暂不可用，请重试");
        }
    }

    private Long currentUserId() {
        UserDTO user = UserHolder.getUser();
        return user == null || user.getId() == null || user.getId() <= 0 ? null : user.getId();
    }

    private long readBitmap(long userId, YearMonth month, int days) {
        List<Long> values = redis.opsForValue().bitField(key(userId, month), BitFieldSubCommands.create()
                .get(BitFieldSubCommands.BitFieldType.unsigned(days)).valueAt(0));
        // 缺失 key 的正常结果是 [0]；null/空结构不代表未签到，必须使整次统计失败。
        if (values == null || values.size() != 1 || values.get(0) == null
                || values.get(0) < 0 || values.get(0) >= (1L << days)) {
            throw new IllegalStateException("Redis签到Bitmap读取结果不明确");
        }
        return values.get(0);
    }

    private String key(long userId, YearMonth month) {
        return prefix + userId + ":" + month.format(DateTimeFormatter.ofPattern("uuuuMM"));
    }
}
