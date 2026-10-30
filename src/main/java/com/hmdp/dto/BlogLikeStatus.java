package com.hmdp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 返回给前端的实时点赞数量和当前用户状态。 */
public record BlogLikeStatus(int liked, @JsonProperty("isLike") boolean isLike) {
}
