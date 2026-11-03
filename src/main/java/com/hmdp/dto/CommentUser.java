package com.hmdp.dto;

/** 新评论接口的所有 ID 使用字符串，避免 JavaScript 大整数舍入。 */
public record CommentUser(String id, String nickName, String icon) {}
