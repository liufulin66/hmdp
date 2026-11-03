# 附近商铺第一版

MySQL 是商铺真实数据来源；Redis GEO 是可重建的位置索引。使用现有 Redis 5.0.14.1、Spring Data Redis 和 MyBatis Plus，没有引入新依赖、消息队列或定位服务。

## 使用前与实际数据

已检查实际 `tb_shop`、`tb_shop_type`、字段、索引与历史坐标：14 家商铺，类型 1 有 9 家，类型 2 有 5 家；另外 8 个已有类型为空。坐标没有缺失、越界或无效类型引用，原字段为 unsigned double。

已有库和新库在基础 SQL 后执行 `sql/migrations/20261101_add_shop_geo.sql`。它添加 `geo_version`、持久化同步任务表和单例构建任务表，将 x/y 扩为有符号 double；不重建商铺表、不修改坐标值。已执行并重复验证迁移；迁移前后全部原商铺字段的摘要一致。不要用含 DROP TABLE 的 `hmdp.sql` 升级已有数据库。

商铺坐标系未记录，不能根据地址或数字猜测 WGS84/GCJ02。按已确认方案，页面默认普通分类，可主动选择“教程演示位置”（120.149993,30.334229）或输入与商铺同源的坐标；没有将演示位置冒充用户位置，没有接入真实 GPS 或擅自转换坐标系。商铺写入支持完整 GEO 合法坐标范围。

## 文件和请求链路

| 文件 | 职责 |
| --- | --- |
| `controller/ShopController.java`、`service/IShopService.java` | 扩展原分类接口，Controller 转交服务 |
| `service/impl/ShopServiceImpl.java` | 参数校验、普通/附近分页、批量详情、写事务与缓存失效编排 |
| `mapper/ShopMapper.java`、`entity/Shop.java` | 行锁、版本递增、分页扫描；版本不接受 HTTP 输入也不对外展示 |
| `dto/ShopPage.java`、`utils/ShopGeoPolicy.java` | 列表/hasMore 与固定边界 |
| `repository/ShopGeoRepository.java` | GEO、元数据、构建和 Lua 调用；支持测试随机前缀 |
| `shop-geo-apply.lua` | 精确版本比较、清理实际旧类型、写入新位置及构建双写 |
| `shop-geo-build.lua`、`shop-geo-read.lua` | 有所有权的临时构建/切换；原子状态检查与附近查询 |
| `entity/ShopGeoTask.java`、`mapper/ShopGeoTaskMapper.java`、`service/impl/ShopGeoSyncService.java` | 持久化任务、领取、租约、提交后首试、重试与失败记录 |
| `entity/ShopGeoBuild.java`、`mapper/ShopGeoBuildMapper.java`、`service/impl/ShopGeoBuildService.java` | 持久化重建请求、分批扫描、租约恢复和成功构建历史 |
| `controller/ShopGeoController.java`、`interceptor/AdminInterceptor.java` | 受管理员权限保护的状态与重建入口 |
| `config/WebExceptionAdvice.java`、`application.yaml` | 参数绑定错误返回 400；调度线程池与轮询 SQL 日志过滤 |
| `frontend/shop-list.html`、`css/shop-list.css`、`js/shop-nearby.js` | 纳入仓库的商铺列表、模式提示、条件切换与分页互斥 |
| `frontend/js/common.js` | 参数序列化保留 0/false，并编码参数值 |
| `ShopGeoIntegrationTest`、`ShopQueryControllerTest`、`ShopServiceImplTest`、`AdminAccessTest`、`shop-nearby.test.cjs` | 真实存储、接口、缓存、权限与前端竞态验证 |

Java 路径相对 `src/main/java/com/hmdp/`，Lua 相对 `src/main/resources/`；测试位于现有测试目录。

查询：`GET /shop/of/type` → `ShopServiceImpl` 校验类型/分页/坐标 → Redis Lua 检查索引并取得附近候选 → 本页 ID 一次批量查 MySQL → 过滤缺失/类型不符、恢复距离顺序 → `Result`。

写入：原管理员 POST/PUT → `ShopServiceImpl` 的 MySQL 事务 → 保存商铺/递增版本/插入完整任务快照 → 提交 → 尝试 GEO 同步，并执行原 `CacheClient.evictAfterCommit` 详情缓存失效。没有在数据库提交前报告投递成功。

## 接口、距离与有界分页

```http
GET /shop/of/type?typeId=1&current=1&pageInfo=true
GET /shop/of/type?typeId=1&current=1&x=120.149993&y=30.334229&radius=5000&pageInfo=true
```

- `x` 为经度，`y` 为纬度；均省略则普通分类，只提供一个则失败。0 合法；NaN、Infinity 和越界值失败。
- 经度范围 [-180,180]，纬度 [-85.05112878,85.05112878]，符合 Redis GEO；typeId 必须为已存在的正 ID。
- `radius` 单位米，默认 5000，可取 1～20000；页大小固定 5，current 为 1～20，两种模式均有页数上限。
- `pageInfo=true` 返回 `data:{list:[],hasMore:false}`；省略该参数继续返回数组，兼容旧调用。新页面显式请求分页结构，拒绝把旧后端的普通数组误认为附近查询。
- 非数字、溢出和缺少必填参数使用 HTTP 400 + Result；可解析但不合法的业务值使用 Result.fail。

每个类型使用 `shop:geo:{typeId}`，member 为商铺 ID。兼容 Redis 5 的 `GEORADIUS ... m WITHDIST ASC COUNT N` 显式按米、距离升序查询，不使用 ANY。

`from=(current-1)*5`，`end=current*5`；查询最近 `end+1` 个原始候选，最多 101 个，再截取 `[from,end)`。仅批量读取本页的至多 5 个 ID，不逐条查询详情，不加载全部商铺计算距离。通过 ID 映射恢复 Redis 顺序，跳过数据库已删除或真实类型不同的记录。项目没有下架状态，本次不新增状态过滤。

`hasMore = current<20 && 原始候选数>end`；即使当前页经数据库过滤后不足 5 条甚至为空，仍可为 true。第 20 页即使看到额外候选也返回 false，表示达到本版查询深度。普通分类采用 ID 升序、LIMIT 6 的有界查询，避免无意义的总数查询。

距离只写入这次批量取得的响应对象，不修改详情缓存。原商铺详情接口继续使用 CacheClient/CachePolicy。

## 版本、重复与乱序同步

更新时先 `SELECT ... FOR UPDATE` 锁住商铺行，合并非 null 字段进行位置/类型校验，再执行局部更新和数据库版本递增，读取完整记录创建任务。只改名称不会让 null 类型或坐标进入任务。新增强制从版本 1 开始；旧记录初始版本为 0。任务唯一键为 `(shop_id,version)`，任务创建失败回滚商铺修改和版本递增。

Redis `state` 哈希保存每家商铺实际已应用的 `版本|类型`。版本按十进制字符串长度及字典序精确比较，不依赖 Lua double 对大整数的精度。旧版本或重复版本不覆盖新位置。

例如数据库连续 A→B→C，C 的任务先到：脚本读取实际已索引的 A，删除 A、加入 C；随后 B 的旧版本被忽略，不会留下 A 或加入 B。版本判断、旧类型清理、GEOADD 与元数据维护在同一个 Lua 中执行，避免“先查版本、再独立写入”的竞争。

MySQL 与 Redis 不是分布式事务，变更到查询可见是最终一致：提交后首次同步失败仍返回原有数据库保存成功结果，数据库任务负责恢复。afterCommit 只负责尝试时序，不是可靠投递来源；可靠恢复依据是同事务创建的任务。详情缓存删除仍使用原有有限重试和 TTL 边界，没有改成持久化缓存失效任务。

任务默认包含首次共 5 次尝试，失败退避 1/5/30/120 秒，扫描间隔 1 秒，每批最多 100 条。领取采用状态、原尝试次数、租约条件 UPDATE，并写随机令牌。PROCESSING 的 next_attempt_at 同步设为租约到期时间，初始任务使用真实当前毫秒值；统一按到期时间、ID 调度。确认必须匹配当前令牌。进程退出后 60 秒租约可回收；最后一次尝试租约过期会保留为 FAILED。

Redis 成功、数据库确认失败时可重复执行，Lua 版本幂等保护结果。Lua 不提供命令错误后的回滚；构建双写可能先成功、活动索引更新失败，持久化任务保留并安全重试。脚本在切换前检查构建完整性，避免边校验边替换。

## 初始化、状态与重建

```http
POST /shop/geo/rebuild
authorization: 管理员原始token

GET /shop/geo/status
authorization: 管理员原始token
```

两个入口都要求现有 `HMDP_ADMIN_USER_IDS` 白名单权限，包含 GET 状态接口；普通商铺查询继续公开。POST 只持久化构建请求，返回构建状态；后台领取执行。并发请求合并到同一个任务，不让普通查询触发全量构建。首次使用须由管理员触发初始化。

构建流程：领取 MySQL 单例任务 → 获取带 TTL 的 Redis 所有权 → 登记所有已有类型（包括空类型）→ 按商铺 ID 每批 150 条扫描 MySQL → 写随机代次临时索引 → 校验并原子切换。最多支持 1000 个类型，避免切换脚本无界遍历类型。

每个临时索引也保存商铺版本和实际类型。构建期间变更任务在同一 Lua 中衔接临时索引；旧扫描快照不能盖掉较新版本。新插入记录也会被分页扫描或任务衔接。即使活动索引未就绪/丢失，任务先衔接构建，再保留失败重试，从而避免长构建中任务次数耗尽造成遗漏。

MySQL 租约与 Redis 所有权都需续期；过期旧持有者不能复活、写入或发布新代次。数据库进程恢复可领取过期任务，并从新临时代次重新构建。构建失败保留原可用索引，临时 key 在 2 小时后过期。成功切换会取消活动 key 的临时 TTL。

索引额外维护 catalog、每类成员数和商铺版本状态。合法空类型的成员数是 0，GEO key 不存在也能明确返回空；应有成员的 key 不存在或数量不符则标记 LOST，不当成附近没有商铺。数据库保存最近成功构建代次，Redis 全部丢失后仍能区分 LOST 与从未初始化的 UNINITIALIZED。Redis 连接不可用显示 UNAVAILABLE。

历史坐标或类型无效时构建失败并指出商铺 ID，保留旧索引，不擅自修复数据。恢复 Redis/修复真实数据后，管理员可以重新触发重建；全量扫描也可恢复任务耗尽后的最新商铺位置。重建不会删除或把历史 FAILED 任务伪装成 SUCCESS。

## 验证与边界

```powershell
mvn -s target/order-task-maven-settings.xml '-Dmaven.compiler.useIncrementalCompilation=false' '-Dhmdp.integration=true' test
node --test frontend/tests/*.test.cjs
git diff --check
```

集成测试使用随机物理表和 Redis 前缀，不启动完整应用，不覆盖业务数据。覆盖半径、米制距离、距离顺序、分页/上限/空类型、零/非法坐标、缺失记录和类型不符；新增/位置/连续换类型/普通字段更新；任务失败、回滚、重试、重复乱序、并发领取与旧令牌；索引丢失、构建失败、并发构建、租约换主，以及扫描与变更交错。另有 MVC 权限/绑定与现有详情缓存回归、前端条件/分页/旧响应/失败重试测试。

实际结果：后端全套 219 项、前端 46 项通过，无失败、无跳过；管理端读取改为按已匹配的控制器识别后，另跑权限回归 11 项通过，包含 matrix URL 变体。`git diff --check` 通过，业务商铺仍为 14 条且原字段摘要一致，随机测试表与 GEO key 已清理。日志保存在忽略的 `target/shop-geo-regression-test.log`、`target/shop-geo-access-test.log` 和 `target/shop-geo-frontend-test.log`。没有覆盖外部 Nginx 文件、重启业务应用、真实浏览器端到端测试、进程强杀测试或大数据压测。第一版不接入浏览器真实定位，无法宣称已验证真实定位和坐标系转换。

分页不提供快照。同距结果沿用 Redis 返回顺序，用户位置变化、索引更新、删除和同步延迟可能使跨页重复或遗漏；前端按 ID 去重只能避免重复展示，不能补回遗漏。条件变化会重置页码，旧请求结果通过代次过滤。

附近索引暂不可用时明确失败，由用户主动切换普通分类，不静默降级。坐标更新但任务未完成时，距离仍可能来自旧索引；类型错配则在本页详情校验时过滤。

本实现面向当前单机 Redis；Lua 跨类型和临时索引使用多 key，不支持直接切换为 Redis Cluster。半径、页数、返回候选与类型数有上限，但精确最近结果仍可能扫描范围内大量匹配点，COUNT 101 不代表 Redis 内部只检查 101 家商铺。稠密区域、全量重建和长期积压需要后续压测及容量规划。

任务有次数上限，不保证故障下的投递时限。SUCCESS 后 Redis 丢失不会自动把所有历史任务重新排队，使用管理员重建恢复；活动索引和成功任务没有自动保留期限。绕过本项目写接口直接修改 MySQL 不会创建同步任务，需要重建，不能宣称这种外部写入也会自动最终一致。

调度线程池配置为 3，避免全量构建占满原来的单调度线程；GEO/Feed 的轮询 Mapper 日志使用 INFO，保留错误日志和业务 DEBUG，避免再次刷掉验证码。
