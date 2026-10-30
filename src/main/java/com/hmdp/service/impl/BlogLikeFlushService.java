package com.hmdp.service.impl;

import com.hmdp.repository.BlogLikeRedisRepository;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class BlogLikeFlushService {
    @Resource
    private BlogLikeRedisRepository likes;
    @Resource
    private BlogLikePersistenceService persistence;
    @Resource
    private RedissonClient redisson;
    @Value("${hmdp.blog-like.batch-size:100}")
    private int batchSize = 100;
    @Value("${hmdp.blog-like.user-batch-size:500}")
    private int userBatchSize = 500;

    @Scheduled(fixedDelayString = "${hmdp.blog-like.flush-interval-ms:5000}")
    public void flush() {
        try {
            for (String value : likes.dirtyIds(Math.max(1, batchSize))) {
                long id = Long.parseLong(value);
                RLock lock = redisson.getLock(likes.lockKey(id));
                if (!lock.tryLock()) {
                    likes.retryLater(id);
                    continue;
                }
                try {
                    BlogLikeSnapshot snapshot = likes.snapshot(id, userBatchSize);
                    if (snapshot == null) {
                        likes.retryLater(id);
                        continue;
                    }
                    persistence.persist(snapshot);
                    // persist 返回时事务已经提交；失败时保留待同步记录。
                    likes.acknowledge(snapshot);
                } catch (RuntimeException e) {
                    log.error("笔记 {} 点赞批量落库失败，保留记录重试", id, e);
                    likes.retryLater(id);
                } finally {
                    lock.unlock();
                }
            }
        } catch (RuntimeException e) {
            log.error("扫描待同步点赞失败，下个周期重试", e);
        }
    }
}
