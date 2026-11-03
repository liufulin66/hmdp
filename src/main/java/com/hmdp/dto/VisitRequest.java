package com.hmdp.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;

public record VisitRequest(PageType pageType) {
    public enum PageType { HOME, SHOP_LIST, SHOP_DETAIL, BLOG_DETAIL, MY_PROFILE, USER_PROFILE }

    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("浏览事件不接受额外字段");
    }
}
