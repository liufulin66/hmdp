# 代码使用说明(本项目来自b站[黑马程序员](https://space.bilibili.com/37974444)[redis教程](https://www.bilibili.com/video/BV1cr4y1671t)，仅供参考)

项目代码包含2个分支：
- master : 主分支，包含完整版代码，作为大家的编码参考使用
- init (包含前端资源): 初始化分支，实战篇的初始代码，建议大家以这个分支作为自己开发的基础代码
  - 前端资源位于init分支src/main/resources/nginx-1.18.0下

视频地址:
- [黑马程序员Redis入门到实战教程，深度透析redis底层原理+redis分布式锁+企业解决方案+redis实战](https://www.bilibili.com/video/BV1cr4y1671t)
- [https://www.bilibili.com/video/BV1cr4y1671t](https://www.bilibili.com/video/BV1cr4y1671t)
  - P24起 实战篇

## 1.下载
克隆完整项目
```git
git clone https://github.com/cs001020/hmdp.git
```
切换分支
```git
git checkout init
```

## 2.常见问题
部分同学直接使用了master分支项目来启动，控制台会一直报错:
```
NOGROUP No such key 'stream.orders' or consumer group 'g1' in XREADGROUP with GROUP option
```
这是因为我们完整版代码会尝试访问Redis，连接Redis的Stream。建议同学切换到init分支来开发，如果一定要运行master分支，请先在Redis运行一下命令：
```text
XGROUP CREATE stream.orders g1 $ MKSTREAM
```

## 3. 秒杀订单数据库约束

`tb_voucher_order` 使用订单 ID 主键保证编号唯一，使用 `(user_id, voucher_id)` 联合唯一索引保证同一用户每张券最多一单。
用户和优惠券字段均为非空；该规则包含未支付、已取消和已退款订单，取消后不会自动恢复购买资格。

- 新建数据库：`hmdp.sql` 已包含该唯一索引。该文件含有 `DROP TABLE`，仅用于初始化，不要用它升级已有数据库。
- 已有数据库：在目标业务数据库中执行 [增量 SQL](sql/migrations/20261025_add_voucher_order_user_voucher_unique.sql)。先检查当前数据库及重复订单；发现重复数据应按业务规则处理，脚本不会自动删单。
- 重复执行：已有等价的双列唯一索引时跳过创建；名称相同但定义不同等异常情况会报错，需要核对现有表结构。

创建后，同一用户、同一券再次插入订单会触发唯一键冲突；不同用户购买同一券、同一用户购买不同券均允许。
下单业务会核对唯一键冲突对应的真实订单，并与库存扣减、处理状态更新放在同一事务内。

## 4. 下单结果、失败补偿与管理员

- 已有数据库先执行 [下单处理记录增量 SQL](sql/migrations/20261028_add_voucher_order_task.sql)，只新增处理记录表，不重建订单表。初始化数据库使用的 `hmdp.sql` 已包含新表。
- `GET /voucher-order/{id}/result`：登录后查询本人订单的 PROCESSING / SUCCESS / FAILED 状态。
- 失败由数据库确认后自动补偿 Redis 库存和购买资格；重复消息不会重复补偿。已有另一笔成功订单时保留购买限制。
- 商户、优惠券的公开查询不变，写入只允许管理员；启动前配置 `HMDP_ADMIN_USER_IDS=实际用户ID,另一个ID`，默认空名单拒绝管理写入。
- 实现机制、升级边界及测试方法见 [秒杀模块文档](docs/seckill-order.md)。

## 5. 用户关注

关注、取关、状态查询及共同关注同步读写 MySQL。已有数据库使用 [关注索引增量迁移](sql/migrations/20261031_add_follow_user_target_unique.sql)，先检查实际表和历史重复关系；不要用含 DROP TABLE 的 `hmdp.sql` 升级已有库。

接口、前端文件、幂等与并发边界及验证见 [关注模块说明](docs/follows.md)。本次前端改动保存在仓库 `frontend/`，未同步到外部 Nginx 目录。

## 6. 关注动态 Feed

发布笔记时，在同一个 MySQL 事务中创建笔记和粉丝投递任务，提交后推送到 `feed:{用户ID}` 收件箱。任务失败可自动重试，重复投递不更新原排序时间。`GET /blog/of/follow` 使用时间和同分偏移游标，每页 2 条。

已有库先执行 [Feed 增量迁移](sql/migrations/20261031_add_feed_delivery_task.sql)。请求链路、重试维护、分页例子、前端文件及性能边界见 [Feed 模块说明](docs/feed.md)。
