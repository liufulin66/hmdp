package com.hmdp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record UvRecent(List<UvDaily> list, String requestedFrom, String requestedTo,
                       String coverageFrom, String coverageTo, Long rangeUv,
                       boolean complete, boolean approximate) {}
