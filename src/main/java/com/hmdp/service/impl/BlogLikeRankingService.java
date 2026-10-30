package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.repository.BlogLikeRedisRepository;
import com.hmdp.service.IUserService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class BlogLikeRankingService {
    @Resource private BlogLikeService blogLikeService;
    @Resource private BlogLikeRedisRepository likes;
    @Resource private BlogLikePersistenceService persistence;
    @Resource private IUserService userService;
    @Resource private RedissonClient redisson;
    @Value("${hmdp.blog-like.ranking-size:3}")
    private int rankingSize = 3;

    public Result queryLikes(Long id) {
        if (id == null || id <= 0) return Result.fail("笔记ID必须为正数");
        try {
            if (!blogLikeService.ensureInitialized(id, null)) return Result.fail("笔记不存在");
            List<Long> ids = ranking(id, Math.max(1, Math.min(rankingSize, 100)));
            if (ids.isEmpty()) return Result.ok(List.of());
            // SQL IN 不保证顺序；只批量查询一次，再按 Redis 的 ID 顺序组装。
            Map<Long, User> users = new HashMap<>();
            userService.listByIds(ids).forEach(user -> users.put(user.getId(), user));
            List<UserDTO> result = new ArrayList<>();
            for (Long userId : ids) {
                User user = users.get(userId);
                if (user == null) continue;
                UserDTO dto = new UserDTO();
                dto.setId(user.getId());
                dto.setNickName(user.getNickName());
                dto.setIcon(user.getIcon());
                result.add(dto);
            }
            return Result.ok(result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.fail("点赞榜单正在初始化，请稍后重试");
        } catch (RuntimeException e) {
            log.warn("笔记 {} 点赞榜单查询失败", id, e);
            return Result.fail("点赞榜单暂不可用，请稍后重试");
        }
    }

    private List<Long> ranking(long id, int limit) throws InterruptedException {
        List<Long> ids = likes.rankedUsers(id, limit);
        if (ids != null) return ids;
        RLock lock = redisson.getLock(likes.lockKey(id));
        if (!lock.tryLock(2, TimeUnit.SECONDS)) throw new IllegalStateException("榜单正在重建");
        String token = UUID.randomUUID().toString();
        boolean building = false;
        try {
            ids = likes.rankedUsers(id, limit);
            if (ids != null) return ids;
            likes.beginRanking(id, token);
            building = true;
            List<BlogLikeChange> users = persistence.loadRankUsers(id);
            for (int start = 0; start < users.size(); start += 500) {
                likes.seedRanking(id, token, users.subList(start, Math.min(start + 500, users.size())));
            }
            likes.seedPendingRanking(id, token);
            likes.publishRanking(id, token);
            building = false;
            ids = likes.rankedUsers(id, limit);
            if (ids == null) throw new IllegalStateException("榜单索引读取失败");
            return ids;
        } finally {
            try {
                if (building) likes.abortRanking(id, token);
            } finally { lock.unlock(); }
        }
    }
}
