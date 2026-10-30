package com.hmdp.service.impl;

import com.hmdp.dto.BlogLikeStatus;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.repository.BlogLikeRedisRepository;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class BlogLikeService {
    @Resource
    private BlogLikeRedisRepository likes;
    @Resource
    private BlogLikePersistenceService persistence;
    @Resource
    private RedissonClient redisson;

    public Result changeLike(Long id, boolean liked) {
        UserDTO user = UserHolder.getUser();
        if (user == null) return Result.fail("请先登录");
        if (id == null || id <= 0) return Result.fail("笔记ID必须为正数");
        try {
            if (!ensureInitialized(id, user.getId())) return Result.fail("笔记不存在");
            return Result.ok(likes.change(id, user.getId(), liked));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.fail("点赞服务繁忙，请重试");
        } catch (RuntimeException e) {
            log.warn("笔记 {} 点赞状态更新失败", id, e);
            return Result.fail("点赞服务暂不可用，请稍后重试");
        }
    }

    public void fillLikes(Blog blog) {
        UserDTO user = UserHolder.getUser();
        Long userId = user == null ? null : user.getId();
        BlogLikeStatus status;
        try {
            if (!ensureInitialized(blog.getId(), userId)) return;
            status = likes.read(blog.getId(), userId);
            if (status == null) throw new IllegalStateException("点赞缓存读取失败");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            status = persistence.readCommitted(blog.getId(), userId);
        } catch (RuntimeException e) {
            log.warn("笔记 {} 点赞缓存不可用，展示已落库状态", blog.getId(), e);
            status = persistence.readCommitted(blog.getId(), userId);
        }
        blog.setLiked(status.liked());
        blog.setIsLike(status.isLike());
    }

    private boolean ensureInitialized(long id, Long userId) throws InterruptedException {
        if (likes.read(id, userId) != null) return true;
        // 冷启动和落库共用每篇笔记的锁，避免从旧数据库快照重建缓存。
        RLock lock = redisson.getLock(likes.lockKey(id));
        if (!lock.tryLock(2, TimeUnit.SECONDS)) throw new IllegalStateException("点赞缓存正在初始化");
        try {
            if (likes.read(id, userId) != null) return true;
            BlogLikeSeed seed = persistence.loadSeed(id);
            if (seed == null) return false;
            likes.initialize(seed);
            return true;
        } finally {
            lock.unlock();
        }
    }
}
