# 关注动态 Feed：推模式与持久化重试

关注关系仍以 MySQL `tb_follow` 为唯一数据来源。关注、取关、状态和共同关注的实现不变。本模块发布时把笔记 ID 分发到粉丝收件箱；收件箱是待展示的笔记排序索引，不是关注人员集合。

## 请求链路和修改文件

发布：`POST /blog` → `BlogController` → `BlogServiceImpl.saveBlog` → 独立代理 `BlogPublicationService.publish` → MySQL 同事务保存笔记和接收人任务 → 提交完成 → `FeedDeliveryService.deliverBlog` → 逐个 `ZADD NX` → 更新投递任务结果。

读取：`GET /blog/of/follow?lastId=...&offset=0` → 原有登录拦截器 → `BlogServiceImpl.queryBlogOfFollow` → Redis 读取当前用户收件箱 → 计算原始条目游标 → 一次批量查询笔记、一次批量查询作者 → 复用 `BlogLikeService.fillLikes` → `Result<ScrollResult>`。

| 文件 | 职责 |
| --- | --- |
| `src/main/java/com/hmdp/controller/BlogController.java`、`service/IBlogService.java` | 新增动态游标查询接口和服务方法 |
| `src/main/java/com/hmdp/service/impl/BlogServiceImpl.java` | 发布编排、登录和分页校验、批量笔记查询、恢复 Redis 顺序、作者与点赞信息 |
| `src/main/java/com/hmdp/service/impl/BlogPublicationService.java` | 独立 `REQUIRES_NEW` 数据库事务，方法返回前完成提交；清除前端笔记 ID、覆盖作者和发布时间 |
| `src/main/java/com/hmdp/entity/FeedDeliveryTask.java`、`mapper/FeedDeliveryTaskMapper.java` | 投递状态、发布时接收人快照、原时间戳、任务领取和带令牌确认 |
| `src/main/java/com/hmdp/service/impl/FeedDeliveryService.java` | 首次逐个投递、定时重试、退避、租约回收、次数上限和失败记录 |
| `src/main/java/com/hmdp/repository/FeedRedisRepository.java` | ZADD NX 和倒序带分数范围查询；测试可注入随机 key 前缀 |
| `src/main/java/com/hmdp/utils/SystemConstants.java` | 每页 2 条、时间戳安全范围及偏移边界 |
| `sql/migrations/20261031_add_feed_delivery_task.sql`、`hmdp.sql` | 任务表、粉丝方向索引；前者用于已有库的非破坏性升级 |
| `sql/migrations/20261031_backfill_feed_initial_due_time.sql` | 修正旧版未尝试任务的零到期时间，不改变已有重试计划和租约 |
| `frontend/info.html`、`frontend/js/feed.js` | 复用“关注”动态标签，刷新、滚动加载、游标、旧响应过滤、状态提示及点赞对象保护 |
| `src/test/java/com/hmdp/service/impl/FeedIntegrationTest.java` | 真实 MySQL/Redis 的隔离集成验证 |
| `src/test/java/com/hmdp/service/impl/BlogServiceImplTest.java`、`config/AdminAccessTest.java` | 发布编排、参数/身份校验、批量顺序及 MVC 访问控制 |
| `frontend/tests/feed.test.cjs` | 重复触底、刷新竞态、失败重试、结束游标、缺失笔记、点赞竞态及真实 Vue 响应式 |
| `README.md`、`frontend/README.md`、本文 | 使用说明、链路、验证与边界 |

`ScrollResult`、`RedisConstants.FEED_KEY`、`Follow` 实体及原关注接口直接复用，没有引入新依赖、消息队列或关注关系 Redis 缓存。没有新增人员列表和数量统计。

## 发布时做了什么

作者从 `UserHolder` 获取。数据库事务使用同一个连接执行：

```sql
INSERT INTO tb_blog (...);

INSERT INTO tb_feed_delivery_task(blog_id,user_id,published_at,next_attempt_at)
SELECT :blogId,user_id,:publishedAt,:publishedAt
FROM tb_follow
WHERE follow_user_id=:authorId;
```

`follow_user_id` 是被关注的作者，`user_id` 才是接收动态的粉丝。一次 `INSERT ... SELECT` 将本次查询到的接收人固定为任务，避免先加载全部粉丝到 Java。所有任务使用同一个服务器毫秒时间戳。任务创建失败会回滚笔记；保存笔记失败也不会投递。

事务放在独立 Bean 中，保证调用经过 Spring 事务代理。独立发布事务提交后，`saveBlog` 才调用 Redis 投递。投递服务使用 `NOT_SUPPORTED`，避免把网络调用和任务确认放入外层长事务。

发布已提交后，即使首次投递部分失败或中断，也返回原格式 `Result.ok(笔记ID)`，未完成任务在 MySQL 中保留。这里没有依靠 `afterCommit` 做可靠投递；提交后执行不等于自动补偿，恢复依据是事先持久化的任务。

推模式将“查询粉丝、固定接收人、为每个粉丝写入笔记索引”的工作放到发布端。读取时不需要查询所有关注作者并合并各作者笔记。

不做关注后的历史回填，也不删除取关前已收到的动态。重试始终使用原接收人，即使之后有人取关或新增关注，也不会改变这批任务。

## 收件箱为什么用 ZSet

例如 `feed:1019` 中，member 是笔记 ID 字符串，score 是原始发布毫秒时间。ZSet 支持按时间范围倒序查询，且同一笔记 ID 只出现一次：

```text
ZADD feed:1019 NX 原始发布时间 笔记ID
```

NX 保证重复投递不会刷新排序时间。不同笔记可能同毫秒发布；同分时沿用 Redis 的 member 倒序字典顺序，不额外假设按数字 ID 大小排序。

投递成功但数据库确认失败时，任务会再次被处理。重复 ZADD NX 无副作用，再次确认成功即可。这是允许重复尝试的幂等投递效果，不是 MySQL 和 Redis 的分布式事务。

## 重试状态与默认参数

状态变化：`PENDING → PROCESSING → SUCCESS`。可重试失败变回 `PENDING`，达到上限进入 `FAILED` 并保留记录。

任务领取使用原尝试次数及状态的条件 UPDATE，成功才递增 attempts、设置随机 claim_token 和租约。确认成功或记录失败时必须匹配令牌，因此旧持有者不能修改已被新持有者领取的任务。

执行进程退出后，PROCESSING 租约到期可以重新领取。若最后一次尝试的持有者退出，则租约过期后转为 FAILED，避免永远卡在 PROCESSING。

可在现有配置中增加：

```yaml
hmdp:
  feed:
    delivery:
      batch-size: 100
      max-attempts: 5
      lease-ms: 60000
      retry-interval-ms: 1000
```

默认总共最多 5 次尝试（包含首次），失败等待依次为 1 秒、5 秒、30 秒、120 秒。定时任务每轮完成后再等待默认 1 秒，每轮最多处理 100 条；不承诺故障时固定一秒内送达。首次投递按任务 ID 分批读取，每批最多 100 条，但会串行尝试该笔记的全部新任务。

首次创建任务显式将 `next_attempt_at` 设置为同一次发布的毫秒时间戳，不依赖表结构的默认值 0。新任务和到期重试统一按 `next_attempt_at ASC, id ASC` 选取，避免持续新增任务以 0 抢占旧重试。相同到期时间按任务 ID 保持确定顺序；这不保证系统过载时的投递时限，也不改变发布请求中首次同步投递的路径。

已有库升级时，先停止旧版本写入，再执行 [首次到期时间回填](../sql/migrations/20261031_backfill_feed_initial_due_time.sql)。脚本只将 `PENDING / attempts=0 / next_attempt_at=0` 且原发布时间有效的历史任务恢复为原发布时间，可重复执行，不调整已有重试退避、租约和终态任务。本次检查实际任务表为空，无需回填业务数据。

FAILED 不自动删除。维护时先查询任务，再针对明确的任务 ID 重置，继续沿用原接收人与发布时间，例如：

```sql
SELECT id,blog_id,user_id,published_at,attempts,last_error
FROM tb_feed_delivery_task WHERE status='FAILED' ORDER BY id LIMIT 100;

-- :taskId 必须替换为已核对的单个失败任务 ID；本次未执行任何业务任务重置。
UPDATE tb_feed_delivery_task
SET status='PENDING',attempts=0,
    next_attempt_at=CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED),
    lease_until=0,claim_token=NULL
WHERE id=:taskId AND status='FAILED';
```

没有新增管理页面或自动清理历史任务的策略。

## 游标分页

接口要求登录，收件箱 ID 只取 `UserHolder`，没有前端指定收件人的入口。`lastId` 必填，允许 0～9007199254740991 的整数毫秒值；offset 默认 0，允许 0～2147483645，预留每页累加空间，避免 int 溢出。非法数值返回 Result.fail，非数字/溢出或缺失必填参数由 MVC 返回 HTTP 400。

Redis 使用 `reverseRangeByScoreWithScores(key, 0, lastId, offset, 2)`。上界包含 lastId，因此下一页必须跳过该时间戳下已经读过的数量。

1. `minTime` 取本次 Redis 实际读到的最后一条分数。
2. 统计本批末尾分数等于 minTime 的条数。
3. 若 minTime 等于请求 lastId，返回 offset 为请求 offset 加本批同分数量。
4. 若 minTime 变早，返回 offset 为本批末尾同分数量。

真实测试例子：8 篇笔记分数同为 1000，另 1 篇分数为 900，每页 2 条。

| 页 | 请求 lastId/offset | 读取笔记 ID | 返回 minTime/offset |
| --- | --- | --- | --- |
| 1 | 2000 / 0 | 17、16 | 1000 / 2 |
| 2 | 1000 / 2 | 15、14 | 1000 / 4 |
| 3 | 1000 / 4 | 13、12 | 1000 / 6 |
| 4 | 1000 / 6 | 11、10 | 1000 / 8 |
| 5 | 1000 / 8 | 18 | 900 / 1 |
| 6 | 900 / 1 | 空 | 0 / 0 |

游标按 Redis 原始条目计算，之后才批量查询笔记。MySQL 中缺失的笔记被略过，但仍消耗游标位置，不会反复卡在同一批。只有 Redis 真正读尽才返回 `{list:[],minTime:0,offset:0}`；有原始条目但全部笔记已缺失时，list 也可为空，但 minTime 仍为正数，前端允许继续加载。

笔记只批量查一次，按 Redis ID 顺序恢复。作者同样批量查询，再复用现有点赞实时状态填充。SQL IN 不保证返回顺序，因此不能直接返回批量查询的原列表。

## 个人主页交互

`info.html` 继续显示关注用户的笔记，进入标签或点击刷新时重置游标。刷新成功替换列表，翻页成功追加；请求失败保留已显示内容，点击重试继续原翻页，刷新失败后的重试仍然执行替换。

pending 阻止重复触底；显式刷新可以使旧翻页失效，每轮 generation/request 标识避免旧响应或旧 finally 覆盖新结果。页面展示加载中、失败重试、空收件箱和到底状态。缺失笔记造成空批次时可点击加载更多，不把 list 为空误判成结束。

动态卡片继续调用 `blogLikes.toggle`。刷新相同笔记时保留原对象及请求期间已改变的点赞值，未完成的点赞响应仍会更新当前显示对象。新增卡片预先声明 likePending，使 Vue 2 可以响应后续禁用状态。返回页面的点赞刷新逻辑保留。

## 数据库与验证

2026-10-31 检查实际库后确认没有 Feed 表。已执行增量迁移，新增投递任务表及 `(follow_user_id,user_id)` 查粉丝索引；重复执行会跳过已有索引。原有 1 条关注关系和 5 篇笔记保留，新业务投递表初始 0 行。不要用含 DROP TABLE 的 `hmdp.sql` 升级已有库。

验证命令：

```powershell
mvn -s target/order-task-maven-settings.xml '-Dmaven.compiler.useIncrementalCompilation=false' '-Dhmdp.integration=true' test
node --test frontend/tests/*.test.cjs
git diff --check
```

原功能交付时的验证结果：后端全套 195 项通过，前端 38 项通过，无失败、无跳过，`git diff --check` 通过。日志保存在忽略的 `target/feed-regression-test.log` 和 `target/feed-frontend-test.log`。

重试公平性修复新增真实 MySQL/Redis 回归：每轮只处理 1 条任务，持续加入较晚到期的新任务，检查旧重试依次成功后继续处理新任务。修复前该用例实际失败，修复后 `FeedIntegrationTest` 10 项与 `BlogServiceImplTest` 9 项全部通过，无失败、无跳过；另验证回填仅影响旧版未尝试的零到期 PENDING 任务，且可重复执行。本轮未重新运行全套或前端测试。日志位于忽略的 `target/feed-fairness-before.log` 和 `target/feed-fairness-after.log`。

Feed 集成测试仅复制真实表结构到随机命名物理测试表，Redis 只写随机前缀。包括发布/任务失败回滚、仅当时粉丝接收、统一时间戳、NX 重推、部分失败后新建工作实例恢复、关注变动不改变任务接收人、真实数据库并发领取、租约回收及旧令牌确认拒绝、重试耗尽、Redis 成功但数据库确认失败后重放、同毫秒跨四页、缺失笔记、空收件箱及身份隔离。点赞填充在 Feed 集成测试中使用替身，点赞真实实现另有既有 MySQL/Redis 集成回归测试。

前端测试覆盖重复触底、刷新与旧请求交错、失败重试、空结果/缺失笔记/结束游标、刷新和点赞交错，并使用本机实际 Vue 2 运行时验证响应式。没有启动完整业务应用、没有停掉真实 Redis 或杀掉业务进程、没有浏览器到 Nginx 和后端的端到端验证，没有大粉丝量压测；进程恢复通过真实任务表加新建工作实例验证。

## 性能和恢复边界

每次发布新增 N 条接收人任务，事务写入量与粉丝数成正比。首次投递仍同步逐个处理，每个接收人涉及领取、Redis 写入和数据库确认；粉丝多时响应会变慢。每批读取任务数量受限，但初次发布并没有把总投递次数限制为一批。现有发布页面请求超时为 2 秒，大粉丝量可能触发客户端超时；目前没有发布幂等键，用户重发 POST 仍可能产生另一篇笔记。

重试是有次数上限的持久化尝试，不保证无限重试或故障下的投递时限。长期故障、持续积压需要监控和人工处理 FAILED。任务表和收件箱都没有自动保留期限，会随业务增长。多实例争抢通过条件领取裁决，但仍可能因租约过期重复执行 Redis；幂等写入和令牌确认保证重复尝试的效果。

MySQL 和 Redis 仍不是分布式事务。任务标记 SUCCESS 后 Redis 数据被淘汰、删除或持久化回退，不会自动转回 PENDING，需要另行重放或重建。事务提交结果不明确、或发布成功响应丢失时，当前没有请求级发布幂等保证。

时间游标不提供跨请求快照。新增更早时间的补投、同分条目中途插入/删除或客户端时钟偏差可能影响当前翻页看到的集合，刷新后重新查询；静态收件箱中的同分分页已验证不重不漏。本次不新增关注历史回填或取关清理。

只更新仓库文件和已授权数据库结构，未提交 Git、未覆盖外部 Nginx 目录、未部署或重启应用。
