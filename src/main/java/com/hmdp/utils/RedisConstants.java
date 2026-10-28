package com.hmdp.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 36000L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";

    public static final String CACHE_SHOP_TYPE_KEY = "cache:shop-type:list";
    public static final Long CACHE_SHOP_TYPE_TTL = 30L;

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    // 商户缓存：正常数据在原有 30 分钟上增加最多 5 分钟抖动。
    public static final long CACHE_SHOP_JITTER_SECONDS = 300L;
    public static final int SHOP_LOCK_MAX_ATTEMPTS = 10;
    public static final long SHOP_RETRY_DELAY_MILLIS = 50L;
    public static final int SHOP_MAX_CONCURRENT_LOADS = 20;
    // 包含首次删除，共尝试 3 次；应用重启后不保留重试任务。
    public static final int SHOP_CACHE_DELETE_MAX_ATTEMPTS = 3;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String SECKILL_ORDER_KEY = "seckill:order:";
    public static final String SECKILL_META_KEY = "seckill:meta:";
    public static final String SECKILL_RESULT_KEY = "seckill:result:";
    public static final String SECKILL_RESERVATION_KEY = "seckill:reservation:";
    public static final String SECKILL_STREAM_KEY = "stream.orders";
    public static final String SECKILL_DEAD_LETTER_KEY = "stream.orders.dead";
    public static final String SECKILL_CONSUMER_GROUP = "g1";
    public static final String SECKILL_CONSUMER_NAME = "consumer-1";
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";
}
