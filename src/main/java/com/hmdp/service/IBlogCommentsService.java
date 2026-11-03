package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.entity.BlogComments;
import com.hmdp.dto.Result;
import com.hmdp.dto.CommentCreateRequest;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogCommentsService extends IService<BlogComments> {

    Result publish(CommentCreateRequest request);
    Result queryRoots(Long blogId,String cursor,Integer size);
    Result queryReplies(Long rootId,String cursor,Integer size);
    Result deleteComment(Long id);

}
