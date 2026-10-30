package com.hmdp.service.impl;

import com.hmdp.dto.BlogLikeStatus;
import com.hmdp.entity.Blog;
import com.hmdp.mapper.BlogLikeMapper;
import com.hmdp.mapper.BlogMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 独立 Bean，确保定时任务通过 Spring 代理开启数据库事务。 */
@Service
public class BlogLikePersistenceService {
    @Resource
    private BlogMapper blogMapper;
    @Resource
    private BlogLikeMapper blogLikeMapper;
    @Value("${hmdp.blog-like.sql-batch-size:200}")
    private int sqlBatchSize = 200;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public BlogLikeSeed loadSeed(long blogId) {
        Blog blog = blogMapper.selectById(blogId);
        if (blog == null) return null;
        Long version = blogLikeMapper.selectVersion(blogId);
        return new BlogLikeSeed(blogId, blog.getLiked() == null ? 0 : blog.getLiked(),
                version == null ? 0 : version, blogLikeMapper.selectLikedUsers(blogId));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public BlogLikeStatus readCommitted(long blogId, Long userId) {
        Blog blog = blogMapper.selectById(blogId);
        if (blog == null) return new BlogLikeStatus(0, false);
        return new BlogLikeStatus(blog.getLiked() == null ? 0 : blog.getLiked(),
                userId != null && blogLikeMapper.isLiked(blogId, userId) > 0);
    }

    @Transactional(readOnly = true)
    public List<BlogLikeChange> loadRankUsers(long blogId) {
        return blogLikeMapper.selectRankUsers(blogId);
    }

    @Transactional
    public void persist(BlogLikeSnapshot snapshot) {
        blogLikeMapper.ensureSyncRow(snapshot.blogId());
        long committedVersion = blogLikeMapper.lockVersion(snapshot.blogId());
        // 全局版本仅供缓存重建生成后续序号，不能跳过同一版本的其他分批。
        List<BlogLikeChange> accepted = new ArrayList<>();
        int delta = 0;
        int chunkSize = Math.max(1, Math.min(sqlBatchSize, 500));
        if (!snapshot.changes().isEmpty()) {
            for (int start = 0; start < snapshot.changes().size(); start += chunkSize) {
                List<BlogLikeChange> chunk = snapshot.changes().subList(start,
                        Math.min(start + chunkSize, snapshot.changes().size()));
                Map<Long, BlogLikeChange> stored = new HashMap<>();
                blogLikeMapper.selectStates(snapshot.blogId(), chunk).forEach(row -> stored.put(row.userId(), row));
                for (BlogLikeChange change : chunk) {
                    BlogLikeChange before = stored.get(change.userId());
                    if (before != null && change.version() <= before.version()) continue;
                    accepted.add(change);
                    delta += (change.liked() ? 1 : 0) - (before != null && before.liked() ? 1 : 0);
                }
            }
        }
        for (int start = 0; start < accepted.size(); start += chunkSize) {
            blogLikeMapper.upsertStates(snapshot.blogId(), accepted.subList(start,
                    Math.min(start + chunkSize, accepted.size())));
        }
        // 总数只增减本批真正改变的关系，和用户状态一起提交。
        if (!accepted.isEmpty() && blogLikeMapper.incrementTotal(snapshot.blogId(), delta) != 1) {
            throw new IllegalStateException("待同步的笔记不存在或点赞总数异常: " + snapshot.blogId());
        }
        blogLikeMapper.updateVersion(snapshot.blogId(), Math.max(committedVersion, snapshot.version()));
    }
}
