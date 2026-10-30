-- 已检查本机已有 tb_blog_like，但没有 version 列。只补字段，保留现有关系和数量。
SET @hmdp_blog_like_version_sql = (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE tb_blog_like ADD COLUMN version BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT ''用户最近一次已落库变更版本'' AFTER liked',
        'SELECT 1')
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tb_blog_like' AND COLUMN_NAME = 'version'
);
PREPARE hmdp_blog_like_version_statement FROM @hmdp_blog_like_version_sql;
EXECUTE hmdp_blog_like_version_statement;
DEALLOCATE PREPARE hmdp_blog_like_version_statement;
