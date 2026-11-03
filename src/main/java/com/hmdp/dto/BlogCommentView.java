package com.hmdp.dto;

import java.time.LocalDateTime;
public record BlogCommentView(String id, String blogId, String parentId, String answerId,
        CommentUser author, CommentUser answerUser, String content, LocalDateTime createTime,
        boolean deleted, boolean canDelete, boolean canReply, long replyCount) {}
