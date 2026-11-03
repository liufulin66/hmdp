package com.hmdp.dto;

import lombok.Data;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
@Data
@JsonIgnoreProperties(ignoreUnknown=true)
public class CommentCreateRequest {
    private Long blogId;
    private String content;
    private Long answerId;
    private String requestId;
}
