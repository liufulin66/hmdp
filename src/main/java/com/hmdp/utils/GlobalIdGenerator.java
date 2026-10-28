package com.hmdp.utils;

import com.baomidou.mybatisplus.core.incrementer.DefaultIdentifierGenerator;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;

/**
 * 统一雪花 ID 入口。显式发号与 MyBatis-Plus ASSIGN_ID 共用同一序列实例。
 * 由 Spring 创建单例；业务代码应注入此对象，不要为每次请求创建生成器。
 */
public final class GlobalIdGenerator implements IdentifierGenerator {
    private final DefaultIdentifierGenerator delegate;

    public GlobalIdGenerator(long workerId, long datacenterId) {
        requireNodeId(workerId, "hmdp.id-generator.worker-id");
        requireNodeId(datacenterId, "hmdp.id-generator.datacenter-id");
        this.delegate = new DefaultIdentifierGenerator(workerId, datacenterId);
    }

    /** 在执行 Redis 预占、发送消息等操作前取得订单编号，无需先写数据库。 */
    public long nextId() {
        return delegate.nextId(null);
    }

    /** 用于需要字符串编号的调用方，例如避免前端 Number 丢失大整数精度。 */
    public String nextIdString() {
        return Long.toString(nextId());
    }

    /** MyBatis-Plus 在实体使用 ASSIGN_ID 且未设置主键时调用。 */
    @Override
    public Long nextId(Object entity) {
        return nextId();
    }

    private static void requireNodeId(long value, String property) {
        if (value < 0 || value > 31) {
            throw new IllegalArgumentException(property + " 必须在 0～31 之间，当前值：" + value);
        }
    }
}
