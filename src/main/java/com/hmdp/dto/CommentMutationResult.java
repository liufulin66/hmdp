package com.hmdp.dto;

public record CommentMutationResult(BlogCommentView comment, long commentCount, String countVersion,
                                    BlogCommentView root) {}
