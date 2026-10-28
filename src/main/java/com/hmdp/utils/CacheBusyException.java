package com.hmdp.utils;

/** 缓存重建等待超时或回源额度不足，与“数据不存在”分开表达。 */
public class CacheBusyException extends RuntimeException {
    public CacheBusyException() {
        super("查询繁忙，请稍后重试");
    }
}
