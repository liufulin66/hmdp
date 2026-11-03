package com.hmdp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
@JsonInclude(JsonInclude.Include.ALWAYS)
public record CommentPage(List<BlogCommentView> list, String nextCursor, boolean hasMore,
                          long commentCount, String countVersion, BlogCommentView root) {}
