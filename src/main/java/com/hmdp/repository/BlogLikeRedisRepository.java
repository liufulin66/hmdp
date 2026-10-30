package com.hmdp.repository;

import com.hmdp.dto.BlogLikeStatus;
import com.hmdp.service.impl.BlogLikeSeed;
import com.hmdp.service.impl.BlogLikeChange;
import com.hmdp.service.impl.BlogLikeSnapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import java.util.*;

@Repository
public class BlogLikeRedisRepository {
    private static final DefaultRedisScript<Long> INIT = script("blog-like-init.lua", Long.class);
    private static final DefaultRedisScript<List> CHANGE = script("blog-like-change.lua", List.class);
    private static final DefaultRedisScript<List> SNAPSHOT = script("blog-like-snapshot.lua", List.class);
    private static final DefaultRedisScript<Long> ACK = script("blog-like-ack.lua", Long.class);
    private static final DefaultRedisScript<Long> INDEX = script("blog-like-index.lua", Long.class);
    private final StringRedisTemplate redis;
    private final String prefix;

    @Autowired
    public BlogLikeRedisRepository(StringRedisTemplate redis) {
        this(redis, "blog:like:");
    }

    /** 测试使用随机前缀，不接触真实点赞 key。 */
    public BlogLikeRedisRepository(StringRedisTemplate redis, String prefix) {
        this.redis = redis;
        this.prefix = prefix;
    }

    public List<String> keys(long id) {
        return List.of(prefix + "state:" + id, prefix + "changes:" + id, prefix + "dirty", prefix + "pending:" + id);
    }

    public String lockKey(long id) { return prefix + "lock:" + id; }

    public BlogLikeStatus read(long id, Long userId) {
        List<Object> values = redis.opsForHash().multiGet(keys(id).get(0),
                List.of("_liked", "_version", "_flushed", "u:" + userId));
        if (values.stream().allMatch(Objects::isNull)) return null;
        if (values.get(0) == null || values.get(1) == null || values.get(2) == null) {
            throw new IllegalStateException("点赞缓存元数据缺失");
        }
        return new BlogLikeStatus(Integer.parseInt(values.get(0).toString()),
                userId != null && values.get(3) != null);
    }

    public void initialize(BlogLikeSeed seed) {
        List<String> args = new ArrayList<>(List.of(Long.toString(seed.blogId()),
                Integer.toString(seed.liked()), Long.toString(seed.version())));
        seed.users().forEach(id -> args.add(id.toString()));
        redis.execute(INIT, keys(seed.blogId()), args.toArray());
    }

    public BlogLikeStatus change(long id, long userId, boolean liked) {
        List<?> result = redis.execute(CHANGE, keys(id), Long.toString(id), Long.toString(userId),
                liked ? "1" : "0", Long.toString(System.currentTimeMillis()));
        if (result == null || result.size() != 2) throw new IllegalStateException("点赞操作没有返回结果");
        return new BlogLikeStatus(Integer.parseInt(result.get(0).toString()), "1".equals(result.get(1).toString()));
    }

    public BlogLikeSnapshot snapshot(long id) {
        return snapshot(id, 500);
    }

    public BlogLikeSnapshot snapshot(long id, int userBatchSize) {
        int limit = Math.max(1, Math.min(userBatchSize, 1000));
        // 旧格式按游标分轮升级，不在单轮 HGETALL 全量重建。
        Long indexed = redis.execute(INDEX, keys(id), Integer.toString(limit));
        if (!Long.valueOf(1).equals(indexed)) return null;
        List<?> result = redis.execute(SNAPSHOT, keys(id), Integer.toString(limit));
        if (result == null || result.size() < 2 || (result.size() - 2) % 3 != 0) {
            throw new IllegalStateException("点赞快照格式错误");
        }
        List<BlogLikeChange> changes = new ArrayList<>();
        for (int i = 2; i < result.size(); i += 3) {
            String value = result.get(i + 2).toString();
            if (!value.equals("0") && !value.equals("1")) throw new IllegalStateException("点赞状态格式错误");
            changes.add(new BlogLikeChange(Long.parseLong(result.get(i).toString()), value.equals("1"),
                    Long.parseLong(result.get(i + 1).toString())));
        }
        return new BlogLikeSnapshot(id, Integer.parseInt(result.get(0).toString()),
                Long.parseLong(result.get(1).toString()), changes);
    }

    public void acknowledge(BlogLikeSnapshot snapshot) {
        List<String> args = new ArrayList<>(List.of(Long.toString(snapshot.blogId()),
                Long.toString(snapshot.version()), Long.toString(System.currentTimeMillis())));
        snapshot.changes().forEach(change -> {
            args.add(Long.toString(change.userId()));
            args.add(Long.toString(change.version()));
            args.add(change.liked() ? "1" : "0");
        });
        redis.execute(ACK, keys(snapshot.blogId()), args.toArray());
    }

    public Set<String> dirtyIds(int batchSize) {
        Set<String> ids = redis.opsForZSet().rangeByScore(prefix + "dirty", 0,
                System.currentTimeMillis(), 0, batchSize);
        return ids == null ? Set.of() : ids;
    }

    public void retryLater(long id) {
        redis.opsForZSet().add(prefix + "dirty", Long.toString(id), System.currentTimeMillis() + 5000);
    }

    private static <T> DefaultRedisScript<T> script(String path, Class<T> type) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(type);
        return script;
    }
}
