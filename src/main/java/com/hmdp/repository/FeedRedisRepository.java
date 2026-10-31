package com.hmdp.repository;

import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

@Repository
public class FeedRedisRepository {
    private final StringRedisTemplate redis;
    private final String prefix;

    @Autowired
    public FeedRedisRepository(StringRedisTemplate redis) { this(redis, RedisConstants.FEED_KEY); }

    /** 集成测试使用随机前缀，不能清理真实 feed key。 */
    public FeedRedisRepository(StringRedisTemplate redis, String prefix) {
        this.redis = redis;
        this.prefix = prefix;
    }

    public String key(long userId) { return prefix + userId; }

    public void push(long userId, long blogId, long publishedAt) {
        Boolean added = redis.opsForZSet().addIfAbsent(key(userId), Long.toString(blogId), publishedAt);
        // false 表示之前已经投递；同样按成功处理，不修改原 score。
        if (added == null) throw new IllegalStateException("Feed投递结果不明确");
    }

    public List<Entry> read(long userId, long lastId, int offset) {
        var tuples = redis.opsForZSet().reverseRangeByScoreWithScores(key(userId), 0, lastId,
                offset, SystemConstants.FEED_PAGE_SIZE);
        if (tuples == null) throw new IllegalStateException("Feed读取结果不明确");
        List<Entry> entries = new ArrayList<>(tuples.size());
        for (var tuple : tuples) {
            Double score = tuple.getScore();
            if (score == null || !Double.isFinite(score) || score <= 0
                    || score > SystemConstants.MAX_FEED_TIMESTAMP || score != Math.floor(score)) {
                throw new IllegalStateException("Feed时间戳异常");
            }
            long id = Long.parseLong(tuple.getValue());
            if (id <= 0) throw new IllegalStateException("Feed笔记ID异常");
            entries.add(new Entry(id, score.longValue()));
        }
        return entries;
    }

    public record Entry(long blogId, long timestamp) {}
}
