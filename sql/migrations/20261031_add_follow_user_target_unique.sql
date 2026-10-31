-- 在实际业务数据库执行。只补索引，不创建、清空或重建 tb_follow。
SELECT DATABASE() AS current_database;

-- 有结果时先停止并确认历史数据清理方案；脚本不会删除或合并关系。
-- 即使继续执行，ADD UNIQUE 遇到重复也会失败，保留原有数据。
SELECT user_id, follow_user_id, COUNT(*) AS relation_count
FROM tb_follow GROUP BY user_id, follow_user_id HAVING COUNT(*) > 1;

-- 任意名称、任意列顺序的等价双列唯一键均可兜底关系唯一性。
SET @follow_has_unique = (
    SELECT COUNT(*) FROM (
        SELECT INDEX_NAME FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tb_follow'
        GROUP BY INDEX_NAME
        HAVING MAX(NON_UNIQUE) = 0 AND COUNT(*) = 2
           AND GROUP_CONCAT(COLUMN_NAME ORDER BY COLUMN_NAME SEPARATOR ',') = 'follow_user_id,user_id'
           AND SUM(SUB_PART IS NOT NULL) = 0
    ) matching_unique_indexes
);
SET @follow_unique_ddl = IF(@follow_has_unique > 0,
    'SELECT ''equivalent follow unique index already exists'' AS migration_result',
    'ALTER TABLE tb_follow ADD UNIQUE KEY uk_follow_user_target (user_id, follow_user_id) USING BTREE');
PREPARE follow_unique_stmt FROM @follow_unique_ddl;
EXECUTE follow_unique_stmt;
DEALLOCATE PREPARE follow_unique_stmt;

-- 反向的等价唯一键不能高效支持按 user_id 查询；必要时补正向查询索引。
-- 正常新增的正向唯一键已经满足要求，不再重复创建普通索引。
SET @follow_has_lookup = (
    SELECT COUNT(*) FROM (
        SELECT INDEX_NAME FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tb_follow' AND IS_VISIBLE = 'YES'
        GROUP BY INDEX_NAME
        HAVING SUM(SEQ_IN_INDEX = 1 AND COLUMN_NAME = 'user_id' AND SUB_PART IS NULL) = 1
           AND SUM(SEQ_IN_INDEX = 2 AND COLUMN_NAME = 'follow_user_id' AND SUB_PART IS NULL) = 1
    ) matching_lookup_indexes
);
SET @follow_lookup_ddl = IF(@follow_has_lookup > 0,
    'SELECT ''follow lookup index already exists'' AS migration_result',
    'ALTER TABLE tb_follow ADD INDEX idx_follow_user_target (user_id, follow_user_id) USING BTREE');
PREPARE follow_lookup_stmt FROM @follow_lookup_ddl;
EXECUTE follow_lookup_stmt;
DEALLOCATE PREPARE follow_lookup_stmt;

SHOW INDEX FROM tb_follow;
