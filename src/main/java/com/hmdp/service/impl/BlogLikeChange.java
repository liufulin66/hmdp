package com.hmdp.service.impl;

/** 一名用户的最终状态及其最近一次变更版本。 */
public record BlogLikeChange(long userId, boolean liked, long version) {
    public BlogLikeChange {
        if (userId <= 0 || version < 0) throw new IllegalArgumentException("Invalid user like change");
    }
}
