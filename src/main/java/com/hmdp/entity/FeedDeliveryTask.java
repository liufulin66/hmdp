package com.hmdp.entity;

import lombok.Data;

/** 每条任务固定一篇笔记、一个接收人和原始发布时间。 */
@Data
public class FeedDeliveryTask {
    private Long id;
    private Long blogId;
    private Long userId;
    private Long publishedAt;
    private String status;
    private Integer attempts;
    private Long nextAttemptAt;
    private Long leaseUntil;
    private String claimToken;
    private String lastError;
}
