# 基础签到

## 请求流程与文件

`POST /user/sign` → 现有 RefreshTokenInterceptor / LoginInterceptor → UserController → IUserService / UserServiceImpl → UserSignService → Redis SETBIT。

`GET /user/sign/status` 经同一登录链路用 GETBIT 查询今天；`GET /user/sign/calendar?month=2026-11` 用 BITFIELD 一次读取月历。没有修改 MvcConfig 的放行范围，登录和 `/user/{id}` 保留原行为。用户和签到日期只从登录上下文及后端时钟取得，客户端传入的 userId/date 不参与处理。

| 主要文件 | 职责 |
| --- | --- |
| `UserController`、`IUserService`、`UserServiceImpl` | 三个接口及服务委托 |
| `service/impl/UserSignService.java` | 身份校验、日期、原子签到、月历解析、Redis 错误处理 |
| `config/BusinessTimeConfig.java` | 提供可注入的 signClock，固定业务时区 Asia/Shanghai |
| `dto/SignResult.java`、`SignStatus.java`、`SignCalendar.java` | 明确响应字段 |
| `frontend/info.html`、`frontend/js/user-sign.js`、`frontend/css/user-sign.css` | 签到、月份导航、请求状态和过期响应保护 |
| `UserSignServiceTest`、`UserSignIntegrationTest`、`AdminAccessTest` | 日期、真实 Redis 并发、错误及登录回归 |
| `frontend/tests/user-sign.test.cjs`、`user-sign.browser.cjs` | 前端竞态、Vue 编译、实际浏览器验收 |

不增加数据库表，不修改积分或其他业务计数。不提供他人记录、补签、撤销、连续签到或奖励。

## 核心方法与原理

### 原子签到

UserSignService.sign 每次请求只执行一次 `LocalDate.now(clock)`，后续月份、key、offset 和返回日期都来自这个值。Clock 统一为上海时区，即使服务器默认时区不同也不影响业务日期。

用户 1001 在 2026 年 11 月的 key 是 `sign:1001:202611`，复用 RedisConstants.USER_SIGN_KEY。第 d 天对应 offset=d-1，第 1/3/31 天分别是 0/2/30。

`SETBIT key offset 1` 原子返回旧位：0 表示本次首次签到，1 表示此前已经签到。因此同一天重复点击、并发和超时重试都只保留一个标记；不需要分布式锁，也没有“先查询再写入”的并发窗口。

若执行抛出异常或返回 null，不知道命令是否已执行，返回 `success=false` 和“结果尚未确认”，不声称成功。客户端可以查询状态，也可以重复 POST。状态和月历查询失败同样明确报错，不伪装为未签到或空记录。

### 月历与计数

calendar 校验严格四位年份、两位月份，拒绝空串、空白、非法月份、0000 年和未来月份；省略 month 才采用服务端当前月。大小月及闰年由 YearMonth.lengthOfMonth 计算。

例如 11 月执行 `BITFIELD sign:1001:202611 GET u30 0`。Redis offset 0 在读取整数的最高位，第 d 天从 `(bitmap >>> (30-d)) & 1` 取得；从 1 到 30 顺序解析得到 signedDays，signCount 直接取列表长度。两者来自同一份读取结果，响应内部一致。月外的位不会计入，只发出一次 Redis 月历读请求。

### 前端状态

登录 `/user/me` 成功后才初始化签到模块，今天显示服务端 date。月历星期位置使用返回月份和 UTC 计算，不用浏览器本地日期判断今天。

提交中与已经签到的按钮禁用。超时后将签到状态标为待确认，保留查询状态和幂等重试入口。刷新状态可跨午夜重新取得服务端日期；长期不刷新页面时不自动声称已进入新的一天。

状态、月历、提交有独立请求编号和 WeakMap 页面上下文。切月和销毁会让旧响应失效，旧 finally 不能清除新请求的 pending；签到前发出的读请求在提交时与成功时失效。历史日期仅为文本，没有补签点击行为。月历失败保留同月已显示内容，切月失败不会把另一月内容当成本月。

## 接口示例

首次 POST `/user/sign`，无需请求体：

```json
{"success":true,"data":{"date":"2026-11-03","signed":true,"newSign":true}}
```

重复 POST 返回 newSign=false。GET `/user/sign/status`：

```json
{"success":true,"data":{"date":"2026-11-03","signed":true}}
```

GET `/user/sign/calendar?month=2026-11`：

```json
{"success":true,"data":{"month":"2026-11","daysInMonth":30,"signedDays":[1,3],"signCount":2}}
```

无记录时 signedDays=[]，signCount=0；读查询不创建记录。业务参数错误沿用项目 Result.fail；未登录由原拦截器返回 HTTP 401。

## 实际验证与本机同步

2026-11-03 实际执行：

- Maven `-Dhmdp.integration=true package`：261 项测试通过，0 失败/错误/跳过，打包成功。
- Node 全套前端测试：111 项通过，0 失败/跳过。覆盖重复点击、超时与查询/重试、切月、旧响应、销毁/重建、失败保留、上海跨月及实际 Vue 观察/模板编译。
- 真实 Redis 随机前缀测试：36 个并发 SETBIT 只有一个 newSign=true；不同用户隔离；月初/月末、普通二月/闰年二月/31 天、午夜跨月、空记录不建 key、无 TTL、错误类型失败。测试结束仅删除自己记录的 key。
- 实际通过 Nginx + 新后端 + 无头 Edge，浏览器设为 America/Los_Angeles：打开个人主页 → 首次签到 → 重复 POST → 刷新仍已签到 → 查看历史月历 → 返回本月；页面/脚本/样式均 HTTP 200，匿名三个接口均 401，浏览器脚本错误为 0。使用随机隔离测试身份，不写 MySQL；其登录及签到 key 在 finally 删除，并验证不存在。
- 没有进行持久化故障恢复、进程强杀或长期负载测试；没有操作真实用户的签到记录。

运行示例：

```powershell
mvn -s target/order-task-maven-settings.xml '-Dmaven.compiler.useIncrementalCompilation=false' '-Dhmdp.integration=true' package
node --test frontend/tests/*.test.cjs
# 可选实际浏览器验收工具装在忽略的 target/ 下，不增加应用依赖：
npm install --prefix target/sign-browser-tools --no-save --package-lock=false playwright
$env:HMDP_PLAYWRIGHT_MODULE=(Resolve-Path target/sign-browser-tools/node_modules/playwright).Path
node frontend/tests/user-sign.browser.cjs
```

浏览器验收需要本机 Redis CLI、Edge、Nginx 与已加载新接口的后端。路径可用 HMDP_REDIS_CLI、HMDP_EDGE_PATH、HMDP_SIGN_BASE_URL 覆盖；Redis CLI 支持其标准环境配置。验收脚本会创建并清理随机测试会话，不使用或输出真实用户 token。

仅同步以下三个文件到 `D:\JavaCode\nginx-1.18.0\html\hmdp`：info.html、js/user-sign.js、css/user-sign.css。脚本和样式版本为 `20261103-sign-2`。原文件备份在 `D:\JavaCode\nginx-1.18.0\backups\hmdp-sign-20261103-140950`，manifest.json 记录哪些目标原先存在及其校验值。没有覆盖其他页面或公共资源。

原先没有后端监听 8081，本次启动新打包的 jar，并通过实际 HTTP 与页面验证了新接口。运行日志在忽略的 target/sign-runtime.log；浏览器结果和截图分别为 target/sign-browser-result.json、target/sign-profile-browser.png。后续检查 IDE Debug 启动问题时，发现该验收进程仍占用 8081，已停止它并确认端口释放，供 IDE 调试使用。

## Redis 存储边界

每月最多 31 位，Redis 字符串位内容最多 4 字节，另有 key 和对象开销。月记录不设置 TTL，查询和应用重启不清理历史。

Redis 是本期唯一签到存储，未双写 MySQL。无 TTL 不等于绝对不丢数据：Redis 数据丢失、人工删除、持久化失败或故障恢复到旧快照都会影响记录。本机核对到 appendonly=no，RDB save 为 `900 1 300 10 60 10000`，maxmemory-policy=noeviction；最近尚未进入快照的数据可能丢失，内存不足会拒绝写入。本次不修改 Redis 持久化配置。需要更强保障时应另行确定 AOF、备份恢复和存储可靠性要求。
