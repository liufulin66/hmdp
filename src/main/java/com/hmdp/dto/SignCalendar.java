package com.hmdp.dto;

import java.util.List;

public record SignCalendar(String month, int daysInMonth, List<Integer> signedDays, int signCount) {}
