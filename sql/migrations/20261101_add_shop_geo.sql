-- 已核对实际 tb_shop、索引和 14 条历史坐标；不重建商铺表，不修改已有坐标值。
SELECT DATABASE() AS current_database;
SET @shop_geo_has_version = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tb_shop' AND COLUMN_NAME='geo_version');
SET @shop_geo_version_ddl = IF(@shop_geo_has_version=0,
    'ALTER TABLE tb_shop ADD COLUMN geo_version bigint unsigned NOT NULL DEFAULT 0',
    'SELECT ''geo_version already exists'' AS migration_result');
PREPARE shop_geo_stmt FROM @shop_geo_version_ddl;
EXECUTE shop_geo_stmt;
DEALLOCATE PREPARE shop_geo_stmt;
SET @shop_geo_unsigned = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tb_shop' AND COLUMN_NAME IN ('x','y') AND COLUMN_TYPE LIKE '%unsigned%');
SET @shop_geo_coord_ddl = IF(@shop_geo_unsigned>0,
    'ALTER TABLE tb_shop MODIFY COLUMN x double NOT NULL COMMENT ''经度'', MODIFY COLUMN y double NOT NULL COMMENT ''纬度''',
    'SELECT ''signed coordinates already supported'' AS migration_result');
PREPARE shop_geo_stmt FROM @shop_geo_coord_ddl;
EXECUTE shop_geo_stmt;
DEALLOCATE PREPARE shop_geo_stmt;
CREATE TABLE IF NOT EXISTS tb_shop_geo_task (
    id bigint unsigned NOT NULL AUTO_INCREMENT,
    shop_id bigint unsigned NOT NULL,
    version bigint unsigned NOT NULL,
    type_id bigint unsigned NOT NULL,
    x double NOT NULL,
    y double NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'PENDING',
    attempts int unsigned NOT NULL DEFAULT 0,
    next_attempt_at bigint unsigned NOT NULL,
    lease_until bigint unsigned NOT NULL DEFAULT 0,
    claim_token varchar(32) DEFAULT NULL,
    last_error varchar(1000) DEFAULT NULL,
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY(id), UNIQUE KEY uk_shop_geo_version(shop_id,version),
    KEY idx_shop_geo_due(status,next_attempt_at,id), KEY idx_shop_geo_lease(status,lease_until,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='商铺GEO持久化同步任务';
CREATE TABLE IF NOT EXISTS tb_shop_geo_build (
    id int NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'IDLE',
    claim_token varchar(32) DEFAULT NULL,
    lease_until bigint unsigned NOT NULL DEFAULT 0,
    requested_at bigint unsigned NOT NULL DEFAULT 0,
    last_successful_generation varchar(32) DEFAULT NULL,
    last_error varchar(1000) DEFAULT NULL,
    PRIMARY KEY(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='GEO全量构建任务及所有权';
SET @shop_geo_has_generation = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='tb_shop_geo_build' AND COLUMN_NAME='last_successful_generation');
SET @shop_geo_generation_ddl = IF(@shop_geo_has_generation=0,
    'ALTER TABLE tb_shop_geo_build ADD COLUMN last_successful_generation varchar(32) DEFAULT NULL',
    'SELECT ''build generation already exists'' AS migration_result');
PREPARE shop_geo_stmt FROM @shop_geo_generation_ddl;
EXECUTE shop_geo_stmt;
DEALLOCATE PREPARE shop_geo_stmt;
