package com.hmdp.service.impl;

import java.util.List;

/** 一次数据库一致性读取的结果，供 Redis 冷启动使用。 */
public record BlogLikeSeed(long blogId, int liked, long version, List<Long> users) {
}
