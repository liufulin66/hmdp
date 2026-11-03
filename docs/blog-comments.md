# 探店笔记评论：MySQL 同步事务

## 已实现的请求流程

发表：`POST /blog-comments` → `BlogCommentsController` 接收 `CommentCreateRequest` → 登录上下文获取作者 → 校验内容/编号 → 锁笔记行 → 检查提交编号 → 校验目标并推导一级评论归属 → 插入评论、原子增加根回复数和笔记评论数 → 同一事务提交 → 返回展示对象、根评论及当前计数。

删除：`DELETE /blog-comments/{id}` → 验证登录 → 非加锁读取定位笔记 → 先锁笔记行，再锁评论 → 校验作者 → 条件逻辑删除 → 正常评论首次删除才减少有效计数 → 同一事务提交。重复删除返回当前占位和计数，不再次扣减。

读取：两个 GET 方法标注 `@AnonymousRead`，登录拦截器只对已匹配的这两个 GET/HEAD 方法放行，不开放整个路径。Service 在只读一致快照中读取计数与 size+1 条评论，批量查询回复目标和公开用户资料，转换为 DTO 并隐藏不可见内容。

## 主要文件

| 文件 | 职责 |
| --- | --- |
| `controller/BlogCommentsController.java` | 四个评论接口及匿名读取标记 |
| `service/IBlogCommentsService.java`、`service/impl/BlogCommentsServiceImpl.java` | 校验、关系推导、事务、幂等、计数、滚动分页及 DTO 组装 |
| `mapper/BlogCommentsMapper.java` | 插入、当前读、条件删除、根回复计数和游标 SQL |
| `entity/BlogComments.java` | 审核状态改为 Integer；独立 deleted、requestId、replyCount；不使用 @TableLogic |
| `mapper/BlogMapper.java`、`entity/Blog.java` | 笔记行锁、原子计数及计数版本；版本不暴露到原笔记接口 |
| `service/impl/BlogPublicationService.java` | 新笔记强制评论数及版本为 0，不接受前端伪造初始计数 |
| `dto/CommentCreateRequest.java` | 只接受 blogId、answerId、content、requestId |
| `dto/BlogCommentView.java`、`CommentUser.java`、`CommentPage.java`、`CommentMutationResult.java` | 字符串 ID、公开用户信息、占位、独立分页和计数版本 |
| `utils/CommentCursor.java` | 时间与 ID 组成的严格 Base64URL 游标，保留微秒 |
| `annotation/AnonymousRead.java`、`interceptor/LoginInterceptor.java` | 精确开放评论查询，写接口仍需登录 |
| `config/WebExceptionAdvice.java` | 复用 Result；非法 JSON/类型绑定等请求错误返回 400 |
| `sql/migrations/20261103_add_blog_comments.sql` | 表字段、索引、计数历史归档及已确认差异校准 |
| `frontend/blog-detail.html`、`frontend/js/blog-comments.js` | 评论输入、回复、删除、独立分页、超时重试、代次与计数版本保护 |
| 后端及前端测试 | 真实 MySQL 事务/并发、接口、精度、Vue 编译与前端竞态 |

Java 路径相对 `src/main/java/com/hmdp/`。`frontend/README.md`、根 README 和本文说明使用方法。此前附近商铺的未提交改动保留，未覆盖外部部署目录。

## 实际数据库核实与迁移

核实 MySQL 8.0.46 中的实际表后再迁移：

- `tb_blog_comments` 原来为空，只有主键；content 是 varchar(255)，时间精度为秒，status 是可空 tinyint unsigned，注释为 0 正常、1 被举报、2 禁止查看。
- `tb_blog` 有 5 篇笔记。笔记 4 的 comments=104，但明细为 0；笔记 23 的 comments=NULL。其余计数为 0。
- 用户确认方案 1A 后，先把 `104` 与 `NULL` 原值归档到 `tb_blog_comments_count_audit`，再仅校准这两条已确认差异为 0。没有删除笔记或评论，不擅自修复其他记录。

增量 SQL 已实际执行，并重复执行验证。复用已有两张业务表，content 扩为 500，时间升级到 TIMESTAMP(6)，添加独立删除标记、可空 ASCII 二进制提交编号和根有效回复数；笔记添加计数版本，comments 设为非空默认 0。

唯一键为 `(user_id,request_id)`，旧记录保持 request_id=NULL，可兼容多个历史记录；没有给历史记录填相同编号。分页索引为 `(blog_id,parent_id,create_time DESC,id DESC)`。

计数校准 SQL 限定本机经确认的 ID、旧值且不存在正常未删除明细，保留原 update_time；重复执行不会重复归档或扣减。其他数据库执行前必须先核对实际历史数据，不能假设也只有这两条差异。不要运行带 DROP TABLE 的初始化 SQL 升级现有库。

## 接口示例

发表一级评论，登录头沿用原始 token，不加 Bearer：

```http
POST /blog-comments
authorization: 登录token
Content-Type: application/json

{"blogId":"4","content":"环境不错，下次还来。","requestId":"d5193ace-64c6-47ea-a361-6574791b9045"}
```

成功结果示例（ID 为示意值；root 与 comment 均返回完整根评论对象）：

```json
{
  "success": true,
  "data": {
    "comment": {
      "id": "100", "blogId": "4", "parentId": "0", "answerId": "0",
      "author": {"id": "1019", "nickName": "用户", "icon": ""},
      "content": "环境不错，下次还来。", "createTime": "2026-11-03T10:00:00.123456",
      "deleted": false, "canDelete": true, "canReply": true, "replyCount": 0
    },
    "commentCount": 1, "countVersion": "2",
    "root": {
      "id": "100", "blogId": "4", "parentId": "0", "answerId": "0",
      "author": {"id": "1019", "nickName": "用户", "icon": ""},
      "content": "环境不错，下次还来。", "createTime": "2026-11-03T10:00:00.123456",
      "deleted": false, "canDelete": true, "canReply": true, "replyCount": 0
    }
  }
}
```

回复 100 时仍调用 POST，传 `answerId:"100"` 和新的 requestId；产生 101，服务端设 parentId=100、answerId=100。回复 101 产生 102 时，仍设 parentId=100、answerId=101。客户端不能指定真实作者、parentId、审核状态、时间或计数；多余字段不参与业务。

```http
GET /blog-comments/of/blog/4?size=10
GET /blog-comments/100/replies?size=10
DELETE /blog-comments/101
```

GET 返回 `data:{list,nextCursor,hasMore,commentCount,countVersion,root}`，空列表明确为 `[]`、nextCursor=null、hasMore=false。一级列表 root=null；回复列表额外返回当前根对象，便于更新根的删除状态和有效回复数。下页只需原样传回 nextCursor，不要解析后用 JavaScript Date 重新生成。

DELETE 与 POST 复用 `CommentMutationResult`。匿名可读、不可写；只有作者可删除自己的评论。默认每页 10 条，最大 50 条。业务校验失败沿用 Result.fail；类型错误、非法 JSON、缺少必填参数返回 HTTP 400。

## 核心方法与原因

`publish` 和 `deleteComment` 使用独立的 READ COMMITTED 事务，统一先锁所属笔记行，再锁目标/根评论。第一版以笔记为单位串行化修改，避免“刚校验允许回复，目标却同时被删除”的竞争，也避免多处反向加锁。

评论内容去除首尾 Unicode 空白后校验，按 Unicode 码点限制 500，与 utf8mb4 VARCHAR(500) 匹配，500 个 emoji 也可保存。requestId 接受 1～64 位字母、数字、下划线或短横线；前端优先用 crypto 生成。

`blogs.adjustComments` 直接执行数据库 `comments=comments+delta` 并递增 comment_version；回复数同样原子维护。只有条件逻辑删除首次成功且原 status=0 才减有效计数。发现应扣减但计数为 0 时抛错回滚，不使用 GREATEST 或简单截到 0 掩盖数据问题。

幂等先查 `(当前用户,requestId)`，比较所属笔记、直接回复目标及规范化内容。完全一致才返回原对象；不同则拒绝。不同 requestId 可以发表相同内容，其他用户也可使用相同编号。原记录被删除后仍保留编号和原始内容用于核对，返回时只能是占位，绝不重新创建。

数据库唯一约束是最终防线。仅将 MySQL 1062/23000 且命中 `uk_blog_comment_user_request` 的冲突进入幂等核对，其他数据库异常继续抛出。冲突等待结束后使用 FOR UPDATE 当前读取得已提交原结果，避免旧快照看不到记录；插入失败不会增加计数。

`queryRoots/queryReplies` 在只读一致快照中读取计数与列表。游标编码数据库时间（固定 6 位微秒）及正 Long ID，严格校验格式和完整性。SQL 条件是 `create_time<time OR (create_time=time AND id<id)`，排序为 create_time DESC,id DESC。查询 size+1 条，以本页实际最后一条生成游标，不把探测记录作为游标。根与回复各自独立分页。

`views` 对当前页涉及的回复目标做一次批量查询，再对作者及被回复用户做一次批量查询，只组装 ID、昵称、头像；不逐条查询用户。所有新接口 Long ID 和计数版本按字符串返回，不改动全站既有数字 ID 契约。

## 删除、审核状态与前端

按用户确认的 2A：status=0 且未删除才返回正文并允许回复；1 返回“该评论已被举报，暂不可见”，2 返回“该评论暂不可见”，不计有效评论。已有正常回复可继续读取。逻辑删除独立于审核状态，不改变原状态含义。

根被删除且仍有正常回复时保留占位；没有正常回复时从一级列表隐藏。删除回复保留“该评论已删除”及必要归属和公开用户信息，后续回复仍能说明是在回复谁。已删除或不可见的根/目标都拒绝新回复。没有给 deleted 配置 @TableLogic，避免框架把占位过滤掉。

前端模块在每次新意图提交时生成 requestId；超时或返回不确定时保留原编号与请求内容。修改内容/回复目标后生成新编号。pending 禁止重复提交，失败保留草稿。成功按 ID 去重并及时插入根或对应楼层，回复在展开时独立加载。

页面切换、刷新、回复收起/重新展开都使用上下文/请求代次校验，旧响应和旧 finally 不得覆盖新状态或解除新 pending。计数版本用十进制字符串比较；较旧快照不能回滚新计数、还原已删除正文或抹掉刚发表内容。评论使用文本插值，保留现有点赞、关注和图片滑动。

## 实际验证和边界

```powershell
mvn -s target/order-task-maven-settings.xml '-Dmaven.compiler.useIncrementalCompilation=false' '-Dhmdp.integration=true' test
node --test frontend/tests/*.test.cjs
git diff --check
```

后端全套 241 项通过；补充 Unicode 空白和非法 JSON 边界后，相关 33 项复验通过；前端 61 项通过，无失败或跳过。包括真实 MySQL 唯一键并发冲突、多个用户原子计数、插入/计数失败回滚、重复删除、删除/回复竞争、同微秒多页大 ID、占位和资料批量查询；EXPLAIN 确认使用滚动索引且没有 filesort。前端覆盖超时复用编号、编辑后新编号、独立回复状态、过期响应、删除/新发表与分页交错，以及本机实际 Vue 运行时的响应式和整页模板编译。日志在忽略的 target/comment-*.log 中。

测试仅使用随机物理表；未清空本地业务表、未往业务表插入测试评论。没有进行真实浏览器端到端测试、网络故障进程强杀或高并发压测。本次不提交 Git、不部署、不覆盖外部 Nginx 页面、不重启业务应用。

第一版同一笔记的写入串行，热点笔记的吞吐受行锁影响。分页不提供跨请求严格快照，插入/删除或审核变化可能改变之后看到的集合，前端去重不能保证补回所有并发遗漏。没有缓存、点赞、热门排序或审核管理入口。

以后新增审核状态变更时，也必须遵循笔记行锁及有效计数/根回复数/版本的同事务更新；绕过协议直接改数据库会造成计数不一致。幂等依赖逻辑删除记录和编号保留，不能随意物理清理后仍承诺历史重试不重复。计数版本保护响应顺序，不是跨设备编辑的全局快照。
