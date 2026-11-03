-- 已核实实际表与历史明细（0 条）；用户确认归档后校准 blog 4:104->0、blog 23:NULL->0。
-- 仅在目标业务库执行；升级期间暂停相关写入。不创建或清空 tb_blog/tb_blog_comments。
SELECT DATABASE() AS current_database;
ALTER TABLE tb_blog_comments MODIFY COLUMN content varchar(500) NOT NULL,
    MODIFY COLUMN create_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    MODIFY COLUMN update_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6);

SET @comment_ddl=IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tb_blog_comments' AND COLUMN_NAME='deleted')=0,
    'ALTER TABLE tb_blog_comments ADD COLUMN deleted tinyint unsigned NOT NULL DEFAULT 0','SELECT ''deleted exists'' AS migration_result');
PREPARE comment_stmt FROM @comment_ddl; EXECUTE comment_stmt; DEALLOCATE PREPARE comment_stmt;
SET @comment_ddl=IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tb_blog_comments' AND COLUMN_NAME='request_id')=0,
    'ALTER TABLE tb_blog_comments ADD COLUMN request_id varchar(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL','SELECT ''request_id exists'' AS migration_result');
PREPARE comment_stmt FROM @comment_ddl; EXECUTE comment_stmt; DEALLOCATE PREPARE comment_stmt;
SET @comment_ddl=IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tb_blog_comments' AND COLUMN_NAME='reply_count')=0,
    'ALTER TABLE tb_blog_comments ADD COLUMN reply_count int unsigned NOT NULL DEFAULT 0','SELECT ''reply_count exists'' AS migration_result');
PREPARE comment_stmt FROM @comment_ddl; EXECUTE comment_stmt; DEALLOCATE PREPARE comment_stmt;
SET @comment_ddl=IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tb_blog' AND COLUMN_NAME='comment_version')=0,
    'ALTER TABLE tb_blog ADD COLUMN comment_version bigint unsigned NOT NULL DEFAULT 0','SELECT ''comment_version exists'' AS migration_result');
PREPARE comment_stmt FROM @comment_ddl; EXECUTE comment_stmt; DEALLOCATE PREPARE comment_stmt;
SET @comment_ddl=IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tb_blog_comments' AND INDEX_NAME='uk_blog_comment_user_request')=0,
    'ALTER TABLE tb_blog_comments ADD UNIQUE KEY uk_blog_comment_user_request(user_id,request_id)','SELECT ''request unique index exists'' AS migration_result');
PREPARE comment_stmt FROM @comment_ddl; EXECUTE comment_stmt; DEALLOCATE PREPARE comment_stmt;
SET @comment_ddl=IF((SELECT COUNT(*) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tb_blog_comments' AND INDEX_NAME='idx_blog_comments_thread_scroll')=0,
    'ALTER TABLE tb_blog_comments ADD INDEX idx_blog_comments_thread_scroll(blog_id,parent_id,create_time DESC,id DESC)','SELECT ''comment scroll index exists'' AS migration_result');
PREPARE comment_stmt FROM @comment_ddl; EXECUTE comment_stmt; DEALLOCATE PREPARE comment_stmt;

CREATE TABLE IF NOT EXISTS tb_blog_comments_count_audit (
    migration_key varchar(64) CHARACTER SET ascii NOT NULL,
    blog_id bigint unsigned NOT NULL,
    previous_comments int unsigned DEFAULT NULL,
    corrected_comments int unsigned NOT NULL,
    recorded_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY(migration_key,blog_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='经用户确认的评论历史计数校准归档';
START TRANSACTION;
SELECT id,comments FROM tb_blog WHERE id IN (4,23) FOR UPDATE;
INSERT INTO tb_blog_comments_count_audit(migration_key,blog_id,previous_comments,corrected_comments)
SELECT '20261103_add_blog_comments',b.id,b.comments,0 FROM tb_blog b
WHERE ((b.id=4 AND b.comments=104) OR (b.id=23 AND b.comments IS NULL))
    AND NOT EXISTS(SELECT 1 FROM tb_blog_comments c WHERE c.blog_id=b.id AND c.status=0 AND c.deleted=0)
ON DUPLICATE KEY UPDATE blog_id=VALUES(blog_id);
UPDATE tb_blog b JOIN tb_blog_comments_count_audit a ON a.blog_id=b.id AND a.migration_key='20261103_add_blog_comments'
SET b.comments=a.corrected_comments,b.comment_version=b.comment_version+1,b.update_time=b.update_time
WHERE b.comments <=> a.previous_comments
    AND NOT EXISTS(SELECT 1 FROM tb_blog_comments c WHERE c.blog_id=b.id AND c.status=0 AND c.deleted=0);
COMMIT;
-- 其他未确认的 NULL 会导致这里失败，而不是被静默清零；须另行核实后处理。
ALTER TABLE tb_blog MODIFY COLUMN comments int unsigned NOT NULL DEFAULT 0 COMMENT '正常且未删除的评论与回复合计';
SELECT b.id,b.comments,COUNT(c.id) AS normal_details FROM tb_blog b
LEFT JOIN tb_blog_comments c ON c.blog_id=b.id AND c.status=0 AND c.deleted=0
GROUP BY b.id,b.comments HAVING b.comments<>COUNT(c.id);
