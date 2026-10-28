-- 已检查实际 hmdp 库：已有 tb_voucher_order，没有独立的下单处理记录表。
-- 只新增处理记录，不改动订单表、不复制订单数据、不删除已有记录。
-- 在目标 hmdp 数据库执行；重复执行不会重复建表。
CREATE TABLE IF NOT EXISTS `tb_voucher_order_task` (
    `order_id` bigint NOT NULL,
    `user_id` bigint unsigned NOT NULL,
    `voucher_id` bigint unsigned NOT NULL,
    `status` varchar(32) NOT NULL DEFAULT 'PROCESSING' COMMENT 'PROCESSING/SUCCESS/COMPENSATING/FAILED',
    `retry_count` int unsigned NOT NULL DEFAULT 0,
    `failure_reason` varchar(255) DEFAULT NULL,
    `restore_qualification` tinyint(1) NOT NULL DEFAULT 1,
    `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`order_id`),
    KEY `idx_order_task_status` (`status`,`update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='异步下单处理与补偿记录';
