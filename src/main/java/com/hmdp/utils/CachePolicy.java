package com.hmdp.utils;

import java.time.Duration;
import java.util.Objects;

/**
 * 不可变缓存策略。loadGroup 是稳定的业务名称，同名业务在一个实例内共享回源额度。
 * 不要将商户 ID、页码等动态查询参数用作 loadGroup。
 *
 * @param ttl 正常结果有效期
 * @param emptyTtl null 或业务指定的空结果有效期
 * @param jitter 正常结果有效期的最大随机增量
 * @param lockTtl 重建锁租期（不自动续期）
 * @param maxAttempts 获取缓存/锁的最大轮数，包含首次尝试
 * @param retryDelay 锁竞争和删除重试的间隔
 * @param maxConcurrentLoads 同一 loadGroup 在单实例内的最大回源并发
 * @param deleteAttempts 删除缓存的最大尝试次数，包含首次尝试
 */
public record CachePolicy(String loadGroup, Duration ttl, Duration emptyTtl, Duration jitter,
                          Duration lockTtl, int maxAttempts, Duration retryDelay,
                          int maxConcurrentLoads, int deleteAttempts) {
    public CachePolicy {
        if (loadGroup == null || loadGroup.isBlank()) {
            throw new IllegalArgumentException("loadGroup 不能为空");
        }
        requirePositiveMillis(ttl, "ttl");
        requirePositiveMillis(emptyTtl, "emptyTtl");
        requirePositiveMillis(lockTtl, "lockTtl");
        requireNonNegativeMillis(jitter, "jitter");
        requireNonNegativeMillis(retryDelay, "retryDelay");
        // 防止写入 TTL、生成随机上界时发生溢出。
        Math.addExact(ttl.toMillis(), jitter.toMillis());
        Math.addExact(jitter.toMillis(), 1L);
        if (maxAttempts <= 0 || maxConcurrentLoads <= 0 || deleteAttempts <= 0) {
            throw new IllegalArgumentException("尝试次数和回源并发上限必须为正数");
        }
    }

    private static void requirePositiveMillis(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative() || value.toMillis() <= 0) {
            throw new IllegalArgumentException(name + " 必须至少为 1 毫秒");
        }
    }

    private static void requireNonNegativeMillis(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative() || (!value.isZero() && value.toMillis() == 0)) {
            throw new IllegalArgumentException(name + " 必须为零或至少为 1 毫秒");
        }
    }
}
