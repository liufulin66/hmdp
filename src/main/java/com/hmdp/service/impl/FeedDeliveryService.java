package com.hmdp.service.impl;

import com.hmdp.entity.FeedDeliveryTask;
import com.hmdp.mapper.FeedDeliveryTaskMapper;
import com.hmdp.repository.FeedRedisRepository;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class FeedDeliveryService {
    @Resource private FeedDeliveryTaskMapper tasks;
    @Resource private FeedRedisRepository inbox;
    @Value("${hmdp.feed.delivery.batch-size:100}") private int batchSize = 100;
    @Value("${hmdp.feed.delivery.max-attempts:5}") private int maxAttempts = 5;
    @Value("${hmdp.feed.delivery.lease-ms:60000}") private long leaseMs = 60000;

    /** 提交后逐个尝试当次接收人；分页读任务，避免全量加载粉丝到 Java。 */
    public void deliverBlog(long blogId) {
        try {
            long afterId = 0;
            while (true) {
                var batch = tasks.selectNewForBlog(blogId, afterId, limit());
                if (batch.isEmpty()) return;
                for (FeedDeliveryTask task : batch) {
                    afterId = task.getId();
                    deliver(task);
                }
            }
        } catch (RuntimeException e) {
            // 笔记已经提交，不能向发布者报告“创建失败”；未完成任务由定时任务恢复。
            log.error("笔记 {} 首次动态投递中断，数据库任务保留待重试", blogId, e);
        }
    }

    @Scheduled(fixedDelayString = "${hmdp.feed.delivery.retry-interval-ms:1000}")
    public void retry() {
        try {
            long now = System.currentTimeMillis();
            int exhausted = tasks.failExhausted(now, attemptsLimit(), limit());
            if (exhausted > 0) log.error("{} 条Feed任务在最大尝试次数后租约超时，已保留为FAILED", exhausted);
            for (FeedDeliveryTask task : tasks.selectDue(now, attemptsLimit(), limit())) deliver(task);
        } catch (RuntimeException e) {
            log.error("扫描Feed投递任务失败，下个周期重试", e);
        }
    }

    private void deliver(FeedDeliveryTask task) {
        String token = UUID.randomUUID().toString().replace("-", "");
        long now = System.currentTimeMillis();
        int maximum = attemptsLimit();
        if (tasks.claim(task.getId(), task.getAttempts(), maximum, token, now,
                now + Math.max(1000, leaseMs)) != 1) return;
        int attempt = task.getAttempts() + 1;
        try {
            inbox.push(task.getUserId(), task.getBlogId(), task.getPublishedAt());
            tasks.succeed(task.getId(), token);
        } catch (RuntimeException error) {
            boolean exhausted = attempt >= maximum;
            String message = error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
            message = message.substring(0, Math.min(1000, message.length()));
            try {
                tasks.fail(task.getId(), token, exhausted ? "FAILED" : "PENDING",
                        System.currentTimeMillis() + retryDelay(attempt), message);
            } catch (RuntimeException recordingError) {
                log.error("Feed任务 {} 更新失败，等待租约超时恢复", task.getId(), recordingError);
            }
            if (exhausted) log.error("Feed投递达到上限，taskId={},blogId={},userId={}", task.getId(), task.getBlogId(), task.getUserId(), error);
            else log.warn("Feed投递失败，将重试，taskId={},attempt={}", task.getId(), attempt, error);
        }
    }

    private int limit() { return Math.max(1, Math.min(batchSize, 1000)); }
    private int attemptsLimit() { return Math.max(1, Math.min(maxAttempts, 100)); }

    private long retryDelay(int attempt) {
        return switch (attempt) { case 1 -> 1000; case 2 -> 5000; case 3 -> 30000; default -> 120000; };
    }
}
