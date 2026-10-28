-- 为已有数据库补齐“一人一券最多一单”约束。
-- 在目标业务数据库中执行；该脚本不创建、清空或重建业务表。
-- 首次执行前应确认查询结果中的 current_database 是预期数据库。
SELECT DATABASE() AS current_database;

-- 预检查：有结果说明历史数据违反唯一性，需先按业务规则处理。
-- 不自动删除、合并订单；即使忽略预检查，后续 ADD UNIQUE 遇到重复也会失败。
SELECT `user_id`, `voucher_id`, COUNT(*) AS order_count
FROM `tb_voucher_order`
GROUP BY `user_id`, `voucher_id`
HAVING COUNT(*) > 1;

-- 接受任意名称、任意列顺序的等价双列唯一索引，避免重复创建。
-- 多一列（例如 status）的唯一索引不能保证“一人一券”，不视为等价。
SET @voucher_order_has_unique = (
    SELECT COUNT(*)
    FROM (
        SELECT INDEX_NAME
        FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'tb_voucher_order'
        GROUP BY INDEX_NAME
        HAVING MAX(NON_UNIQUE) = 0
           AND COUNT(*) = 2
           AND GROUP_CONCAT(COLUMN_NAME ORDER BY COLUMN_NAME SEPARATOR ',') = 'user_id,voucher_id'
           AND SUM(SUB_PART IS NOT NULL) = 0
    ) AS matching_unique_indexes
);

SET @voucher_order_unique_ddl = IF(
    @voucher_order_has_unique > 0,
    'SELECT ''equivalent unique index already exists'' AS migration_result',
    'ALTER TABLE `tb_voucher_order` ADD UNIQUE KEY `uk_voucher_order_user_voucher` (`user_id`, `voucher_id`) USING BTREE'
);

PREPARE voucher_order_unique_stmt FROM @voucher_order_unique_ddl;
EXECUTE voucher_order_unique_stmt;
DEALLOCATE PREPARE voucher_order_unique_stmt;

-- 验证结果：唯一索引应有两行，Non_unique 为 0，列为 user_id 和 voucher_id。
SHOW INDEX FROM `tb_voucher_order`;
