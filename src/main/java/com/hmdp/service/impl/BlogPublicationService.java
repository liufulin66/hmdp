package com.hmdp.service.impl;

import com.hmdp.entity.Blog;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.mapper.FeedDeliveryTaskMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** 独立代理和事务边界：方法返回前已提交笔记与投递任务，之后才访问 Redis。 */
@Service
public class BlogPublicationService {
    @Resource private BlogMapper blogMapper;
    @Resource private FeedDeliveryTaskMapper taskMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long publish(Blog blog, long authorId) {
        long publishedAt = System.currentTimeMillis();
        blog.setId(null);
        blog.setUserId(authorId);
        blog.setCreateTime(LocalDateTime.ofInstant(Instant.ofEpochMilli(publishedAt), ZoneId.systemDefault()));
        if (blogMapper.insert(blog) != 1) throw new IllegalStateException("发布笔记失败");
        taskMapper.createForFollowers(blog.getId(), authorId, publishedAt);
        return blog.getId();
    }
}
