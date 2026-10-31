package com.hmdp.utils;

public class SystemConstants {
    public static final String IMAGE_UPLOAD_DIR = "D:\\JavaCode\\nginx-1.18.0\\html\\hmdp\\imgs\\";
    public static final String USER_NICK_NAME_PREFIX = "user_";
    public static final int DEFAULT_PAGE_SIZE = 5;
    public static final int MAX_PAGE_SIZE = 10;
    public static final int FEED_PAGE_SIZE = 2;
    // Redis score 为 double，限制在可精确表示整数的范围内。
    public static final long MAX_FEED_TIMESTAMP = 9007199254740991L;
    public static final int MAX_FEED_OFFSET = Integer.MAX_VALUE - FEED_PAGE_SIZE;
}
