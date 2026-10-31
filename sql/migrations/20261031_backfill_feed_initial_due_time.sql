-- 在所有写入实例升级为显式设置首次到期时间的版本后执行，可重复执行。
-- 只修正旧版尚未尝试的 PENDING 任务，不改动重试计划、处理中租约或终态任务。
-- 原始发布时间已到期，恢复为该值后仍可立即调度，不再以 0 永久抢占其他到期任务。
UPDATE tb_feed_delivery_task
SET next_attempt_at = published_at
WHERE status = 'PENDING' AND attempts = 0 AND next_attempt_at = 0 AND published_at > 0;
