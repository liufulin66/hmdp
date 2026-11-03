# 站点 UV 第一版

## 请求链路与文件

业务页面主内容请求成功 → Vue 渲染后 `siteVisits.afterRender` → 独立 axios 实例匿名 POST `/statistics/visits` → 访客 Cookie → StatisticsService 取得一次上海日期 → StatisticsRepository → Lua 的 PFADD 与固定 EXPIREAT。

管理员页面 GET `/statistics/uv/daily`、`/statistics/uv/recent` → 现有令牌识别 → 登录校验 → AdminInterceptor 使用原管理员白名单 → StatisticsService 判断覆盖状态 → 每日或多 key PFCOUNT → Result。

| 文件 | 职责 |
| --- | --- |
| `controller/StatisticsController.java` | 三个接口，明确分开匿名采集和管理员读取 |
| `service/impl/StatisticsService.java` | 单次业务日期、可用状态、固定七日窗口、区间去重、受控错误日志 |
| `repository/StatisticsRepository.java`、`site-uv-record.lua` | HLL 写入、固定过期点、多 key PFCOUNT，拒绝不明确的 Redis 结果 |
| `utils/VisitorCookie.java`、`dto/VisitRequest.java` | 持久 UUIDv4 Cookie、事件白名单、拒绝额外身份/日期/key 字段 |
| `dto/UvDaily.java`、`UvRecent.java` | 明确近似统计、null、覆盖范围与数据状态 |
| `StatisticsProperties`、`StatisticsPolicy`、`BusinessTimeConfig` | 启用日期、Cookie Secure、90日/7日边界及共享上海业务时钟 |
| `AnonymousPost`、`AdminOnly`、两种权限拦截器及 MvcConfig | 按实际匹配方法授权，不放开整段统计路径 |
| 六个业务 HTML、`js/site-visits.js` | 内容成功展示后最多一次上报，不依赖登录 token |
| `statistics.html`、`js/site-statistics.js`、`css/statistics.css` | 每日/趋势、覆盖范围、权限/异常/重试、日期选择过期响应保护 |
| 后端/前端/浏览器测试 | 真实 Redis、Cookie、权限、采集时机、并发及真实页面验收 |

商铺详情原本只在外部 Nginx，已复制为 `frontend/shop-detail.html` 纳入源码，只增加统计引用和成功展示后的调用。没有数据库表、访问明细、消息队列、访客 Set、商铺或笔记各自的 UV。

## 浏览器访客标识

Cookie 名为 `hmdp_visitor_id`，服务端随机生成 UUIDv4；合法旧值继续复用，大小写统一为小写，非法格式、长度或版本重新生成。Path=/、HttpOnly、SameSite=Lax、Max-Age=365天；每次访问续存同一个标识。Cookie 生命周期覆盖90日统计保留期。

HTTP 开发环境 Secure=false；HTTPS 设置 `HMDP_STATISTICS_COOKIE_SECURE=true`。Cookie 不依赖手机号、IP 或登录 token，前端不读取其内容，同源请求自动携带。UUID 只能作为统计标识，不能认证用户或授予管理员权限。

同一浏览器访问多个页面、游客转为登录态、退出登录，均复用同一标识。清除/拒绝 Cookie、不同浏览器、不同域名或隐私模式可能产生新标识；首次没有 Cookie 的多个标签同时请求，可能各自生成不同 UUID，丢失 Set-Cookie 响应后的重试也可能重复计入。此版本不承诺绝对去重，也不声称统计精确自然人数。

## HyperLogLog 与去重

生产 key 为 `uv:site:yyyyMMdd`，例如 `uv:site:20261103`，只使用上海自然日。visitorId 经 PFADD 加入统计摘要。Lua 返回的是“命令处理完成”，没有把 PFADD 的0/1返回值当成精确首次访问。

HyperLogLog 对输入做哈希，分桶记录观测到的前导零特征，据此估算基数；不保存全部原始访客身份。Redis 使用16384个寄存器，稠密表示约12 KiB加头部及 key/对象开销，小样本可使用更小的稀疏表示。标准误差约 `1.04/sqrt(16384)=0.81%`，这是统计误差而非每次结果误差上限；Cookie/网络造成的采集误差也不包含在这个数字里。

日 UV 是一个 key 的 PFCOUNT。区间 UV 使用最多七个日期 key 的多 key PFCOUNT，合并寄存器后估算并集，不做逐日求和，也不创建第二份访客 Set。

例如第一日访客 A/B，第二日 A/C，第三日 A：每日分别约2、2、1，而区间并集约3，不是5。小规模测试可使用确定性样本验证；没有用大规模样本要求零误差。

## 保留期与状态

统计日 D 固定在 `D.plusDays(90)` 的上海00:00过期。当日与前89个自然日属于保留范围；重复上报重新设置的是同一个绝对时间，不从访问时刻滑动延长。

Lua 在写入前校验 UUID、到期时间、key 类型及已有字符串是否为 HLL。PFADD 与 EXPIREAT 在同一个脚本内执行；脚本失败不会自动回滚先前成功的命令，客户端收到失败只表示未确认采集成功，不保证数据没有写入。

`HMDP_STATISTICS_START_DATE` 默认 `2026-11-03`，是本机本次首次上线日期，跨日重启不能改为当天。其他环境首次部署应设置自己的真实启用日期，不能为了填满图表而把日期随意提前。

| 状态 | uv | 含义 |
| --- | --- | --- |
| AVAILABLE | 非负整数，含0 | 已启用、在保留期内，且本次 Redis 查询成功 |
| NOT_COLLECTED | null | 早于实际启用日期，未采集 |
| EXPIRED | null | 曾在配置采集期内，但已超出90日保留范围 |

正常缺失的 HLL key 按空摘要得到0；页面表述为“当日无已采集的浏览记录”，不证明真实世界无人访问。Redis 命令抛错、null 或异常数值返回 Result.fail，不返回伪造的0。Redis 数据丢失或宕机漏报也可能减少统计值，此方案无法仅凭摘要证明采集完整，更不能重建原始访问。

recent 始终返回今天及前六天，按日期升序。未采集/已过期日期不补0；coverageFrom/coverageTo 仅覆盖 AVAILABLE 日期，rangeUv 仅对这些日期去重。没有可用日期时覆盖日期及 rangeUv 都为 null，complete=false；全部七天可用才 complete=true。今天仍是进行中的自然日，首个上线日也只采集功能启用后的事件。

## 接口与权限

POST `/statistics/visits` 请求体仅允许：

```json
{"pageType":"HOME"}
```

白名单为 HOME、SHOP_LIST、SHOP_DETAIL、BLOG_DETAIL、MY_PROFILE、USER_PROFILE。枚举限制格式与长度，未知类型、额外字段（visitorId、date、key 等）拒绝；事件类型不进入 Redis key，不存 URL 或访问明细。

```json
{"success":true,"data":{"collected":true}}
```

`@AnonymousPost` 只放行显式标记方法的 POST；原 `@AnonymousRead` 仍仅用于 GET/HEAD。统计查询由 `@AdminOnly` 和原 `HMDP_ADMIN_USER_IDS` 白名单控制：无登录401，非管理员403。判断基于匹配的 HandlerMethod，matrix URL 也不能绕过。前端没有管理员名单或敏感配置。

GET `/statistics/uv/daily?date=2026-11-03`，省略 date 查询今天；空串、错误日期和未来日期拒绝：

```json
{"success":true,"data":{"date":"2026-11-03","uv":2,"approximate":true,"status":"AVAILABLE"}}
```

GET `/statistics/uv/recent` 返回 list（固定七条）、requestedFrom/To、coverageFrom/To、rangeUv、complete、approximate。首次上线当天只有今天是 AVAILABLE，前六天是 NOT_COLLECTED；页面明确显示覆盖不足七天。

## 前端采集与故障边界

首页等待分类与初始笔记请求均成功；商铺列表等待有效分页成功；详情页等待主体对象成功；个人主页等待用户主体成功。他人主页重定向到自己主页时不会提前上报他人主页事件。Vue 渲染后才发送，销毁或过期目标不发送。

每个 HTML 文档中同一页面类型只尝试一次，不随分页、点赞、评论加载或 Vue 更新重复发送。独立 axios 实例不携带 token，也不使用 common.js 的401跳转和错误弹窗拦截器。采集失败返回给模块的结果为 false，不阻塞内容、弹窗或自动要求登录；这也意味着网络中断、关闭页面或拦截脚本可能漏记。

统计管理页同样使用独立请求实例，按服务端401/403显示错误。每日与最近七日分别有 pending、请求编号和页面上下文；更换日期、刷新或销毁后，旧响应与旧 finally 不能覆盖新结果。加载/错误不展示伪造0，未知日期显示“未采集”或“已过期”。

## 实际验证

2026-11-03 本机实际执行：

- Maven `-Dhmdp.integration=true package`：293项通过，0失败/错误/跳过，构建打包成功。含真实 Redis 5.0.14.1 HLL 的重复、并发、跨日并集、上海午夜、固定过期点、非法 key 类型及状态区分；Cookie 与管理员权限测试通过。
- Node 全套前端：129项通过，0失败/跳过。含六类页面成功/失败上报、分页不重复、采集失败不中断、管理页日期选择过期响应、权限失败与重试、Vue 模板编译，以及原有业务回归。
- 静态检查：七个 HTML 及27个唯一脚本/样式引用均通过本机 Nginx 返回 HTTP 200；同步文件校验值与源码一致。
- 真实无头 Edge + Nginx + 新后端：六类页面采集成功；上报请求无 authorization；Cookie 属性正确，隔离登录态切换和真实退出后 ID 不变；第一浏览器多页面为1 UV，第二浏览器为2 UV；匿名查询401、普通用户403、临时管理员200，包含 matrix URL；管理页七行、六天未采集、实际覆盖今天，日UV和区间UV均为2。
- 浏览器还模拟首次上报断网，内容继续显示；加载缺失笔记不报成功浏览，列表分页不重复上报；浏览器脚本错误为0。旧日期交错主要由 Node 测试验证，未在浏览器中模拟所有网络排列。
- Redis 集成测试使用随机前缀。浏览器验收用随机 `hmdp:test:uv-browser:<UUID>:` 前缀和隔离会话，未污染 `uv:site:*`；临时测试管理员只通过该验收进程参数指定，未修改实际管理员配置。测试结束仅清理自己创建的摘要和会话，并确认不存在。
- 未做大规模压测、Redis持久化/主从故障恢复、真实短信登录全流程；没有修改机器时间或创建数据库访问明细。

日志在忽略的 `target/uv-full-build.log`、`uv-frontend-test.log`、`uv-browser-test.log`；浏览器结果与截图为 `target/uv-browser-result.json`、`target/uv-statistics-browser.png`。浏览器脚本为 `frontend/tests/statistics.browser.cjs`，需要已安装的 Playwright、Edge、Redis CLI 和与 `target/uv-browser-config.json` 匹配的隔离后端，不能直接对正式 UV 前缀运行。

## 同步与运行

已备份后同步10个文件：六个业务 HTML、statistics.html、js/site-visits.js、js/site-statistics.js、css/statistics.css。资源版本 `20261103-uv-1`。备份目录：

`D:\JavaCode\nginx-1.18.0\backups\hmdp-uv-20261103-190331`

manifest.json 记录原文件是否存在及校验值，其他公共脚本、样式、图片与页面未覆盖。管理入口位于自己的个人主页，也可直接打开 `/statistics.html`；读取仍以服务端权限为准。

验收时后端新接口实际可用，并通过了匿名采集及管理员查询。验收完成后停止临时后端，释放8081供 IDE Debug 使用；Nginx 保持运行。日常使用需启动最新后端，并沿用实际管理员白名单；默认使用正式 `uv:site:` 前缀。实现与验收阶段未执行 Git 提交或推送。

## 性能与数据边界

recent 最多读取七个日值及一个最多七 key 的并集，客户端不能指定 key 列表。当前多 key PFCOUNT 验证环境为单机 Redis，不把各命令之间的并发访问看成固定快照。没有为 UV 加分布式锁。

Redis 是本期唯一统计存储，记录到期后不保留原始明细。保留90日不等于故障下绝不丢失；摘要被删除或 Redis 恢复旧快照后无法重建真实历史访问。上报成功也不代表恰好新增一位自然人，0.81%标准误差不包含采集与身份边界。
