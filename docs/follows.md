# 用户关注与共同关注

本模块以 MySQL 的 `tb_follow` 为关注关系的唯一数据来源。关注、取关和查询均同步执行 SQL，不使用 Redis 关注缓存、异步落库或消息队列。现有登录身份识别仍复用项目的 Redis token 和 `UserHolder`，这不属于关注关系缓存。

## 接口与参数

所有接口均要求登录，`authorization` 使用项目现有的原始 token，不加 `Bearer `。

| 请求 | 成功时的 `Result.data` |
| --- | --- |
| `PUT /follow/{followUserId}/true` | `true`，已设置为关注 |
| `PUT /follow/{followUserId}/false` | `false`，已设置为未关注 |
| `GET /follow/or/not/{followUserId}` | 当前用户是否关注目标的布尔值 |
| `GET /follow/common/{followUserId}?current=1` | 本页共同关注的 `UserDTO[]` |
| `GET /user/{id}` | 目标用户的 `UserDTO`，用于接通已有个人主页 |

PUT 不需要请求体。操作人只取 `UserHolder`，不存在允许前端指定操作人 ID 的参数。关注状态只接受小写 `true` / `false`，避免把拼写错误解释成取关。

用户 ID 必须为正数，目标用户必须存在。本人 ID 的规则：关注、取关失败，状态查询返回 `false`，共同关注失败。未登录由现有拦截器返回 HTTP 401；可转换的非法业务参数使用 `Result.fail`，非数字、数值溢出等参数绑定错误由 Spring MVC 返回 HTTP 400。

共同关注指“两个人都关注了哪些用户”。按共同目标的用户 ID 升序，每页固定 `SystemConstants.DEFAULT_PAGE_SIZE = 5`，`current` 默认 1、只接受 1～1000，最大偏移为 4995。不开放前端指定页大小，不查询总数。结果只含 ID、昵称、头像，空结果为 `[]`。

## 请求链路和文件

浏览器 `/api/follow/...` → Nginx 原有 `/api` 代理 → `RefreshTokenInterceptor` 识别登录身份 → `LoginInterceptor` 校验登录 → `FollowController` → `IFollowService` / `FollowServiceImpl` → `FollowMapper` → MySQL → `Result`。

| 文件 | 职责 |
| --- | --- |
| `src/main/java/com/hmdp/controller/FollowController.java` | 三条关注路由、参数绑定及严格布尔参数解析 |
| `src/main/java/com/hmdp/service/IFollowService.java` | 关注设置、状态查询、共同关注的服务契约 |
| `src/main/java/com/hmdp/service/impl/FollowServiceImpl.java` | 身份及目标校验、幂等、分页、资料批量查询及顺序恢复 |
| `src/main/java/com/hmdp/mapper/FollowMapper.java` | INSERT、双条件 DELETE、EXISTS、自关联及冲突索引核对 |
| `src/main/java/com/hmdp/controller/UserController.java` | 新增 `GET /user/{id}` 的路由委派 |
| `src/main/java/com/hmdp/service/IUserService.java`、`impl/UserServiceImpl.java` | 新增用户资料业务，校验 ID 并转换为 `UserDTO` |
| `sql/migrations/20261031_add_follow_user_target_unique.sql` | 已有表的增量索引迁移 |
| `hmdp.sql` | 新安装表定义中的联合唯一索引；不可用于升级已有数据库 |
| `frontend/js/follows.js` | 两页共享的状态查询、pending、固定操作意图、错误恢复与登录引导 |
| `frontend/blog-detail.html` | 详情页关注按钮接入共享逻辑，并绑定 disabled |
| `frontend/other-info.html` | 将已有个人主页纳入仓库，接通关注和共同关注标签、分页及空/错误状态 |
| `frontend/js/common.js` | 将已有共享脚本纳入仓库，修复网络异常没有 `response` 时二次报错的问题 |
| `src/test/java/com/hmdp/service/impl/FollowServiceImplTest.java` | 参数、身份、幂等异常分类、查询上限和批量资料排序 |
| `src/test/java/com/hmdp/service/impl/FollowIntegrationTest.java` | 真实 MySQL、多连接并发及查询正确性 |
| `src/test/java/com/hmdp/config/AdminAccessTest.java` | 实际 MVC 登录拦截、路由参数及响应字段验证 |
| `src/test/java/com/hmdp/service/impl/UserServiceImplTest.java` | 用户资料转换及非法/不存在用户验证 |
| `frontend/tests/follows.test.cjs` | 共享逻辑、页面接线、失败恢复、分页及实际 Vue 响应式验证 |
| `README.md`、`frontend/README.md`、本文 | 入口、文件使用和实现边界说明 |

`Follow` 实体、`Follow` 表字段、`Result`、`UserDTO`、登录拦截器均复用原有结构，无需改动。没有引入新依赖。

## 唯一约束和幂等

`UNIQUE (user_id, follow_user_id)` 让相同用户到相同目标的关系最多存在一条。两个并发请求即使同时尝试 INSERT，也由 InnoDB 唯一键检查决定结果，不能靠先查询再插入来保证。

`follow(..., true)` 直接 INSERT，不使用会忽略多种数据库错误的 `INSERT IGNORE`。发生 `DuplicateKeyException` 后，检查底层 MySQL 错误码 1062、SQLState 23000，并从错误中提取索引名称，再核对实际 `information_schema.STATISTICS`：它必须是恰好覆盖两个关系字段的完整联合唯一键。这样兼容历史等价索引的不同名称，同时把主键或其他唯一键的冲突继续抛出。无法确认的异常不按成功处理。

`follow(..., false)` 使用 `DELETE ... WHERE user_id = ? AND follow_user_id = ?`。影响 0 行意味着该关系已不存在，仍可成功返回 `false`。一次变更只有一条数据修改 SQL，不需要额外分布式锁或跨系统事务。

共同关注核心 SQL：

```sql
SELECT a.follow_user_id
FROM tb_follow a
JOIN tb_follow b
  ON b.user_id = :targetUserId AND b.follow_user_id = a.follow_user_id
JOIN tb_user u ON u.id = a.follow_user_id
WHERE a.user_id = :currentUserId
ORDER BY a.follow_user_id ASC
LIMIT 5 OFFSET :offset;
```

自关联让数据库直接筛选交集，不把双方全部关注集合传到 Java。正向联合索引支持按操作人扫描并按目标 ID 排序，也支持另一方关系的唯一键查找。关联 `tb_user` 排除已经缺失的目标资料。得到最多 5 个 ID 后只调用一次 `listByIds`，使用 ID 到用户的映射按原 SQL 顺序输出 DTO，避免 N+1 查询和 SQL IN 无序问题。

## 数据库迁移

2026-10-31 实际检查本机 `hmdp.tb_follow`：InnoDB，两个关系字段非空，0 条记录，仅有 `id` 主键；没有重复、自关注或悬空关系，也没有表触发器。

已在本机执行增量迁移，新增 `uk_follow_user_target(user_id, follow_user_id)`，记录数保持 0。再次执行迁移验证了已有索引会被跳过。没有创建、重建或清空业务表。

其他环境先检查真实表结构、索引和重复关系，再在目标数据库执行该增量 SQL。有历史重复时必须先报告并确认处理方案；脚本不删除或合并关系，直接继续 ADD UNIQUE 也会因重复而失败。迁移接受任意名称、任意列顺序的等价完整双列唯一索引。如果已有唯一键是反向的，则按需补正向查询索引；正向唯一键已满足查询时不重复增加普通索引。

## 前端交互和使用范围

两个关注按钮绑定 `followPending` 为 disabled，函数本身也拒绝 pending 期间的重复点击，所有出口在 finally 中释放状态。首次状态未知时先查询，成功后只采用后端返回的布尔值。

一次点击在发送 PUT 之前捕获意图；发生错误后显示提示并重查关注状态，因为超时不能证明服务器没有写入。失败意图保留在 `followRetryIntent` 中，按钮显示“重试关注”或“重试取消关注”，重试继续发送原来的 true/false。状态查询失败时可重新查询，不会盲目切换状态。不同目标的旧响应不会覆盖当前目标。

个人主页复用原有“共同关注”标签，不新增整套页面。分页期间禁止重复点击，失败保留已显示页，空页明确展示空状态。没有总数或额外探测查询；满 5 条时允许点击下一页，因此恰好 5 的倍数可能在下一页看到空结果。

本次只更新仓库前端源文件，未覆盖外部 Nginx 目录、未重启应用、未提交 Git、未部署。`other-info.html` 原有“笔记”标签依赖尚未实现的 `/blog/of/user`，本次没有扩展该业务；加载失败在标签内显示提示，不影响关注和共同关注。

## 验证与边界

实际执行：

```powershell
mvn -s target/order-task-maven-settings.xml '-Dmaven.compiler.useIncrementalCompilation=false' '-Dhmdp.integration=true' test
node --test frontend/tests/*.test.cjs
git diff --check
```

后端全套 182 项通过，前端 26 项通过，无失败、无跳过。后端包括真实 MySQL/Redis 的既有回归测试；新增关注集成测试不访问 Redis、不启动完整应用，使用 `CREATE TABLE ... LIKE` 仅复制结构到随机物理测试表，由多个独立连接共享。16 个并发关注全部成功，最终只有一条关系；绕过业务代码的直接重复插入也被真实唯一约束拒绝。测试后删除本次创建的测试表，业务关注行数保持不变。

验证覆盖正常/重复关注和取关、状态查询、不同操作人隔离、共同关注含义和排序、分页和空结果、非法 ID/页码/状态、目标不存在、本人 ID、未登录、异常不可误吞、前端 pending、超时重查、原意图重试和 Vue 响应式更新。没有启动完整应用或进行浏览器到 Nginx、真实后端的端到端操作；没有进行大数据量压力测试。

唯一索引只保证关系不重复，不保证多个相反请求按用户点击时间执行。多设备、不同页面，或超时后仍在服务端继续运行的旧请求，都可能晚于新请求提交。最终状态取决于实际执行顺序；按钮 pending、原意图重试和状态重查不能提供全局顺序。需要这种保证时，应另行设计服务端版本或条件更新协议。

固定页大小限制数据库返回和批量资料查询的数量，但交集很稀疏时数据库仍可能扫描较多索引记录；第 1000 页也需跳过前面记录。并发增删会让 OFFSET 分页出现跨页重复或遗漏，且两次查询之间的资料删除可能使返回条数变少。重复关注需要额外读取索引元数据，极端热门账号还可能存在数据库写入或连接压力。这些上限不是无限规模的性能保证。
