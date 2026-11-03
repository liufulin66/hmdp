package com.hmdp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record UvDaily(String date, Long uv, boolean approximate, Availability status) {
    public enum Availability { AVAILABLE, NOT_COLLECTED, EXPIRED }
}
