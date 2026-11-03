package com.hmdp.utils;

import java.time.LocalDate;

public final class StatisticsPolicy {
    private StatisticsPolicy() {}
    public static final int RETENTION_DAYS = 90;
    public static final int RECENT_DAYS = 7;

    public static LocalDate date(String value) {
        if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") || value.startsWith("0000")) {
            throw new IllegalArgumentException("日期必须采用yyyy-MM-dd格式");
        }
        return LocalDate.parse(value);
    }
}
