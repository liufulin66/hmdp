-- 已检查本机 hmdp 数据库，不存在点赞关系表或点赞同步版本表。
-- 保留 tb_blog.liked 历史总数；不删除、不覆盖现有业务数据。
CREATE TABLE IF NOT EXISTS `tb_blog_like` (
    `blog_id` bigint unsigned NOT NULL,
    `user_id` bigint unsigned NOT NULL,
    `liked` tinyint(1) NOT NULL,
    `version` bigint unsigned NOT NULL DEFAULT 0 COMMENT '用户最近一次已落库变更版本',
    `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`blog_id`, `user_id`),
    KEY `idx_blog_like_state` (`blog_id`, `liked`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='探店笔记用户点赞最终状态';

CREATE TABLE IF NOT EXISTS `tb_blog_like_sync` (
    `blog_id` bigint unsigned NOT NULL,
    `version` bigint unsigned NOT NULL DEFAULT 0,
    `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`blog_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='点赞批量同步的已提交版本';
