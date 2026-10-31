-- 已检查实际 hmdp：没有 Feed 投递表。只新增任务表和必要查询索引，不清理业务数据。
SELECT DATABASE() AS current_database;
CREATE TABLE IF NOT EXISTS tb_feed_delivery_task (
    id bigint unsigned NOT NULL AUTO_INCREMENT,
    blog_id bigint unsigned NOT NULL,
    user_id bigint unsigned NOT NULL COMMENT '发布时确定的收件人',
    published_at bigint unsigned NOT NULL COMMENT '固定的原始发布时间，毫秒',
    status varchar(16) NOT NULL DEFAULT 'PENDING',
    attempts int unsigned NOT NULL DEFAULT 0,
    next_attempt_at bigint unsigned NOT NULL DEFAULT 0,
    lease_until bigint unsigned NOT NULL DEFAULT 0,
    claim_token varchar(32) DEFAULT NULL,
    last_error varchar(1000) DEFAULT NULL,
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_feed_blog_user (blog_id,user_id),
    KEY idx_feed_due (status,next_attempt_at,id),
    KEY idx_feed_lease (status,lease_until,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='关注动态持久化投递任务';

-- user_id 在前的关注唯一键不能高效按作者找粉丝；接受已有的等价前缀索引。
SET @feed_has_follower_index = (
    SELECT COUNT(*) FROM (
        SELECT INDEX_NAME FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tb_follow' AND IS_VISIBLE='YES'
        GROUP BY INDEX_NAME
        HAVING SUM(SEQ_IN_INDEX=1 AND COLUMN_NAME='follow_user_id' AND SUB_PART IS NULL)=1
           AND SUM(SEQ_IN_INDEX=2 AND COLUMN_NAME='user_id' AND SUB_PART IS NULL)=1
    ) matching_indexes
);
SET @feed_follower_index_ddl = IF(@feed_has_follower_index > 0,
    'SELECT ''follower lookup index already exists'' AS migration_result',
    'ALTER TABLE tb_follow ADD INDEX idx_follow_target_user (follow_user_id,user_id)');
PREPARE feed_follower_index_stmt FROM @feed_follower_index_ddl;
EXECUTE feed_follower_index_stmt;
DEALLOCATE PREPARE feed_follower_index_stmt;
