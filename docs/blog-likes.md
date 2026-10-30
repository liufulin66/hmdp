# 探店笔记点赞

登录用户使用 `PUT /blog/like/{id}` 点赞，使用 `DELETE /blog/like/{id}` 取消点赞。两个接口都不需要请求体，身份取自登录令牌。返回的 `data.liked` 是实时总数，`data.isLike` 是当前用户状态。重复 PUT 或重复 DELETE 不会重复计数。

`BlogController` 负责接收请求，`BlogServiceImpl` 调用 `BlogLikeService`。Lua 在 Redis 内完成用户状态检查、计数变更、版本递增、待同步登记，避免多个请求先查询后修改造成重复点赞。同一批内同一用户反复操作，只保留最终状态。

`BlogLikeFlushService` 每轮读取待同步笔记，每篇笔记获取 Redisson 锁、生成有数量上限的 Redis 用户快照，再调用独立的 `BlogLikePersistenceService`。每条用户变更携带自己的版本，数据库只接受比该用户已落库版本更新的状态。Spring 数据库事务一起提交用户关系、这些关系对应的数量增减和同步版本，重试不会重复增减，旧快照也不能恢复旧状态。

分批后不能直接把 Redis 的整篇总数覆盖到数据库：该总数包含尚未处理的其他用户。现在根据本批用户在数据库中的旧状态计算增减，只提交这些已处理关系对应的数量。分批全部处理后数据库总数收敛到 Redis。全局版本仅用于缓存重建后的序号起点，不再据此跳过关系写入，因此同一全局版本的后续分批不会漏写。

数据库提交后才 ACK。ACK 只删除本批中用户版本仍然相同的变更；同一用户又操作过时保留新版本，并移到用户队列后面。仍有未处理用户的笔记也排到已就绪笔记后面，防止热门笔记长期占据同步名额。数据库异常或确认失败时保留记录重试，未获得锁的笔记也延后排队。

默认每轮最多 100 篇，每轮结束后等待 5 秒。可以在配置中覆盖：

```yaml
hmdp:
  blog-like:
    flush-interval-ms: 5000
    batch-size: 100
    user-batch-size: 500
    sql-batch-size: 200
```

这里的批量是把一个周期内的操作合并后写入。每轮最多处理 `batch-size` 篇笔记；每篇最多读取 `user-batch-size` 个用户（硬上限 1000）；每条 SQL 最多处理 `sql-batch-size` 个用户（硬上限 500）。同一篇的本批 SQL 和一次计数增减在一个独立事务内提交。积压较多、数据库异常时，同步延迟可能超过 5 秒。详情、热门列表、我的笔记展示 Redis 实时计数；热门列表的排序依据已落库总数，因此允许短暂延迟。Redis 查询故障时展示已落库状态，写入故障时返回失败供重试。

新环境使用 `sql/migrations/20261030_add_blog_like.sql` 创建 `tb_blog_like`（用户最终点赞状态及版本，联合主键限制每用户每笔记一条记录）和 `tb_blog_like_sync`（重建缓存使用的全局版本）。已有环境使用 `20261030_add_blog_like_user_version.sql`，检查列不存在后才补充用户版本，已有行默认版本 0。迁移可以重复执行，不重置现有关系和计数。旧计数没有对应用户明细，所以保留为历史基数；不能凭总数推断历史点赞者，新功能从上线后开始记录具体关系。

Redis 使用 `blog:like:state:{id}` 保存总数、全局版本和用户关系；`blog:like:changes:{id}` 保存 `用户版本:状态`；`blog:like:pending:{id}` 按待处理顺序索引用户；`blog:like:dirty` 登记待同步笔记。正常快照使用有数量上限的 ZRANGE 和 HGET，不再 HGETALL 读取整篇变更。旧格式由 HSCAN 游标分轮转换，保留已有待提交记录，索引建立完毕才开始分批；HSCAN 的 COUNT 是扫描提示，不是严格条数上限。新旧程序不能混跑，先执行数据库迁移，再统一重启应用。

这些 key 不设置 TTL，因为尚未落库的数据不能按普通缓存过期。不要手动删除其中一部分，也不要与允许随意淘汰 key 的缓存实例混用。部分 key 缺失或用户索引数量不一致时，代码会拒绝盲目覆盖未完成的变更。

这是异步最终一致方案：数据库恢复的是已提交批次。Redis 在提交到数据库之前丢失数据，可能丢失这部分尚未落库的点赞；数据库事务、Lua 和重试不能消除这个窗口。部署时应根据可接受的数据损失配置 Redis 持久化，并使用 `noeviction` 等方式避免淘汰。完整 Redis 故障恢复后需核对已提交版本；不能承诺 Redis 持久化回退时仍自动维持跨系统的严格一致性。

Nginx 中的 `index.html`、`info.html` 和 `blog-detail.html` 根据 `isLike` 分别发送 PUT / DELETE，并直接使用返回状态更新数量和图标。请求期间暂时忽略重复点击。配套页面在 `D:/JavaCode/nginx-1.18.0/html/hmdp`，不在本仓库内。

三个页面通过带版本号的 `<script src="./js/blog-likes.js?v=20261030-2"></script>` 使用仓库中的 `frontend/js/blog-likes.js`。点赞处理显式更新 Vue 2 响应式字段，无论成功或失败都解除按钮的处理中状态。浏览器从历史缓存恢复页面时，不会再次执行 Vue 的 `created`，因此在 `mounted` 中注册 `blogLikes.watch`，在 `beforeDestroy` 中清理监听。`pageshow.persisted` 和重新可见事件会重新查询已展示笔记的实时状态，保留原列表、分页和滚动位置；较旧的查询响应不能覆盖随后发生的点赞操作。

Nginx 对 HTML 响应设置 `Cache-Control: no-cache`，页面脚本使用显式版本号。已经打开的旧页面仍需强制刷新一次才能载入修复，此后正常返回会自动同步。详情不再请求尚未实现的 `/blog/likes/{id}` 点赞用户列表。

前端回归测试覆盖连续点赞/取消、重复点击、失败后恢复、历史返回刷新、旧响应竞争和本机实际 Vue 2 的响应式更新：

```powershell
node --test frontend/tests/blog-likes.test.cjs
```

实际 Vue 运行时默认从上述配套目录读取；其他机器可通过 `HMDP_FRONTEND_ROOT` 指定目录，未找到 Vue 文件时只跳过这一项运行时测试。

集成测试只写当前连接的 MySQL 临时表和随机前缀 Redis key，不启动应用及秒杀消费者：

```powershell
mvn -s target/order-task-maven-settings.xml '-Dmaven.compiler.useIncrementalCompilation=true' '-Dhmdp.integration=true' '-Dtest=BlogLikeIntegrationTest,BlogLikePersistenceServiceTest,BlogLikeServiceTest,BlogLikeFlushServiceTest,BlogServiceImplTest,AdminAccessTest' test
```
