# 秒杀下单模块

## 目标

- 不超卖：Redis Lua 原子扣减 + 数据库条件更新双保险；
- 一人一单：Redis Set 去重 + `(user_id, voucher_id)` 唯一索引兜底；
- 请求快：请求线程只访问 Redis，数据库写入由消费者异步完成；
- 可恢复：Redis Stream 消费组 + ACK + pending 重试 + 数据库处理状态 + 幂等库存补偿。

## 请求链路

```
POST /voucher-order/seckill/{voucherId}
  → 登录拦截器（/voucher-order/** 未被排除）
  → VoucherOrderServiceImpl.createVoucherOrder
      ├─ ensureMeta：Redis 元数据缺失时从数据库补齐，并修复缺失库存
      ├─ GlobalIdGenerator.nextId()：提前生成雪花订单号
      └─ 执行 seckill.lua：
           时间窗口 → 库存 → 一人一单 → 扣库存 → 记录预扣归属 → XADD
  → 立即返回订单号字符串（失败时返回售罄/重复/未开始等提示）
        ↓ 异步
  VoucherOrderConsumer（SmartLifecycle，单线程）
      ├─ XREADGROUP 读取 stream.orders
      ├─ VoucherOrderPersistenceService.persistVoucherOrder（@Transactional）
      │     ├─ 创建并锁定 tb_voucher_order_task（非 PROCESSING 状态不再落单）
      │     ├─ INSERT tb_voucher_order（唯一索引拦截重复消息）
      │     ├─ UPDATE tb_seckill_voucher SET stock = stock - 1 WHERE stock > 0
      │     └─ 同事务更新任务为 SUCCESS
      ├─ 失败：独立事务核对订单并记录重试，确认失败后置 COMPENSATING
      │     └─ Lua 幂等归还库存/资格 → 任务 FAILED → 死信审计
      └─ 成功或补偿完成后 XACK
```

## 下单结果与数据库升级

`GET /voucher-order/{id}/result` 需要登录，只能查询本人的记录。返回统一 `Result`：

```json
{"success":true,"data":{"orderId":"123","voucherId":"10","status":"PROCESSING","message":"订单处理中","compensated":false}}
```

- `PROCESSING`：队列等待、重试中，或已失败但补偿尚未完成。
- `SUCCESS`：数据库订单已存在，另返回 `orderStatus`，表示原有的支付/核销状态。
- `FAILED`：已确认失败且补偿完成，`compensated=true`，`message` 说明是否恢复购买资格。
- 不存在或属于其他用户：统一返回“下单记录不存在”。编号使用字符串，避免前端精度丢失。

订单成功/失败结果以数据库为准。请求只在 Redis 预扣并入队；消费者首次处理时才创建任务。
消费者处理前，接口通过 Lua 同时写入的预扣记录识别本人并返回处理中。历史已落库订单也可直接查询。

2026-10-28 已检查实际 `hmdp` 数据库原有 11 张表：只有业务订单表，没有独立处理记录表。
`tb_voucher_order.status` 的 1～6 表示支付、核销、取消、退款，不能混用作异步处理状态。
新增的 `tb_voucher_order_task` 以 `order_id` 为主键，记录归属、重试次数、处理状态和补偿规则。
已有数据库使用 [增量 SQL](../sql/migrations/20261028_add_voucher_order_task.sql)，不执行带 DROP 的初始化脚本。
增量脚本可重复执行，不修改现有订单表和订单数据；执行前仍应检查目标库及已有同名表的结构。

## 管理接口权限

`GET/HEAD` 商户、优惠券查询保持公开；新增/修改等写操作要求已登录且用户 ID 位于白名单。
未登录返回 HTTP 401，已登录但不在名单返回 HTTP 403。`OPTIONS` 允许正常协商。

```yaml
hmdp:
  admin:
    user-ids: ${HMDP_ADMIN_USER_IDS:}
```

启动前设置 `HMDP_ADMIN_USER_IDS=1,2`（填实际已存在的用户 ID），或在本地配置中填写列表。
默认空名单拒绝所有管理写入。管理员仍使用原有手机验证码登录，不新增账号或修改用户表。

## Redis key 与常量

| 用途 | Key | 说明 |
|---|---|---|
| 秒杀库存 | `seckill:stock:{voucherId}` | 活动期间不设过期时间 |
| 已购用户 | `seckill:order:{voucherId}` | Set，`SISMEMBER` O(1) 去重 |
| 活动元数据 | `seckill:meta:{voucherId}` | Hash：`beginTime`、`endTime`，毫秒时间戳 |
| 用户预扣归属 | `seckill:reservation:{voucherId}` | Hash：userId → orderId，防止旧消息释放新订单的资格 |
| 单次预扣记录 | `seckill:result:{orderId}` | Hash：订单/用户/券 ID、处理中状态、compensated 标记 |
| 订单消息 | `stream.orders` | 全局 Stream，消费组 `g1` |
| 死信消息 | `stream.orders.dead` | 补偿完成的失败消息审计，以及格式异常的原始消息 |

常量集中在 [RedisConstants.java](../src/main/java/com/hmdp/utils/RedisConstants.java)。

## Lua 返回码

| 返回 | 含义 | 接口返回 |
|---|---|---|
| 0 | 抢购成功，消息已入队 | `Result.ok(orderId字符串)` |
| 1 | 库存不足 | `Result.fail("库存不足")` |
| 2 | 重复下单 | `Result.fail("不能重复下单")` |
| 3 | 活动未就绪（元数据/库存缺失或格式错误） | `Result.fail("秒杀活动未就绪")` |
| 4 | 活动未开始 | `Result.fail("秒杀尚未开始")` |
| 5 | 活动已结束 | `Result.fail("秒杀已结束")` |

## 库存与活动时间预热

[VoucherServiceImpl.addSeckillVoucher](../src/main/java/com/hmdp/service/impl/VoucherServiceImpl.java)
在两表事务提交成功后，通过 `TransactionSynchronization.afterCommit` 写入：

- `SET seckill:stock:{voucherId} stock`
- `HSET seckill:meta:{voucherId} beginTime <ms> endTime <ms>`

提交后写入避免事务回滚时留下不存在的活动。Redis 写失败只记日志，不改变已提交的数据库结果。

历史秒杀券没有预热数据时，[VoucherOrderServiceImpl.ensureMeta](../src/main/java/com/hmdp/service/impl/VoucherOrderServiceImpl.java)
会在首次请求时从 `tb_seckill_voucher` 补齐元数据，并用 `SETNX` 补写缺失库存。

## 消费者与失败处理

- **消费组**：启动时执行 `XGROUP CREATE stream.orders g1 0 MKSTREAM`，已存在时按 `BUSYGROUP` 忽略。
- **正常消息**：落库成功后 `XACK`。
- **重复消息**：锁定同一条任务，已有成功订单时直接返回 SUCCESS，不重复扣库存；唯一键异常还必须核对真实订单，不能一律当作成功。
- **临时失败**：不 `XACK`，每 30 秒重试 pending；重试次数记录在数据库，重启不会重置。数据库不可用、无法确认是否落单时不退款、不 ACK。
- **重试上限**：数据库确认 3 次失败后将任务置 COMPENSATING，阻止同一订单的后续落库。补偿完成后置 FAILED，写入死信审计再 ACK。
- **补偿幂等**：Lua 校验预扣订单归属，归还一次库存并标记 compensated。补偿后数据库更新失败也可安全重试；旧补偿不会删除用户重试产生的新预扣。
- **另一笔成功订单**：同一用户、同一券已有其他订单时，只归还本次多预扣的库存，保留已购标记。
- **ACK 失败**：消息仍会重投，重复投递由唯一索引保证幂等。

## 为什么这样设计

1. **Lua 脚本**：Redis 单线程执行脚本，`检查库存 → 检查重复 → 扣减 → 记录 → 发消息` 不会被其他请求穿插，避免 check-then-act 竞态。
2. **Stream 而不是 `@Async` / List**：Stream 有消费组、ACK、pending 列表，消费者崩溃后可恢复；`@Async` 消息在内存里，进程一挂就丢；`BRPOP` 弹出即删除，没有 ACK。
3. **先锁任务，再插订单、扣库存、提交成功状态**：同一任务的成功和失败决策串行化；唯一索引阻止同用户同券的另一笔订单；库存条件更新是最后防线。
4. **事务方法独立成 Bean**：消费者通过 Spring 代理调用 `VoucherOrderPersistenceService`，`@Transactional` 才会生效；同类自调用会绕过代理。
5. **雪花 ID 在 Lua 之前生成**：Redis 预占、Stream 消息、数据库订单共用同一个编号，重试时不会重新发号。

## 已知限制与运维注意

- **单实例消费者名**：当前固定为 `consumer-1`。多实例部署时应为每个实例配置唯一消费者名，并用 `XPENDING` + `XCLAIM`/`XAUTOCLAIM` 认领其他消费者的 pending 消息。
- **时钟同步**：活动时间比较使用应用服务器时间，所有实例应启用 NTP；否则可能出现提前/延迟开抢。
- **Redis Cluster**：脚本访问的库存/订单/元数据 key 与全局 `stream.orders` 可能不在同一 slot，当前实现面向单机 Redis。上集群需使用哈希标签并把 Stream 按券分片。
- **Stream 长度**：为避免裁剪掉尚未 ACK 的消息，`XADD` 未设置 `MAXLEN`。生产环境应监控 `XLEN` 并在确认无 pending 后执行 `XTRIM`。
- **活动数据清理**：库存、资格、元数据、预扣归属和结果标记均不自动过期。只有确认相关消息无 pending、任务均已终结后，才能按保留策略清理；数据库结果仍保留。
- **活动时间修改**：直接修改 `tb_seckill_voucher` 的起止时间后，需要重新写入 `seckill:meta:{voucherId}`，否则脚本仍使用旧时间。
- **活动状态**：当前只校验时间、库存和一人一单，不校验 `tb_voucher.status`；下架券如果仍保留库存和元数据，仍可能被抢购。
- **补偿保护边界**：库存 key 丢失、预扣记录丢失或归属不匹配时，不根据数据库库存盲目回填，任务保留在 COMPENSATING、消息不 ACK。需要恢复正确的 Redis 数据或人工对账。升级前的失败消息没有预扣归属记录，同样需要人工核对；历史正常订单仍可处理。
- **Redis 持久化**：请求入队到首次消费之间尚无数据库任务，恢复能力依赖 Redis Stream/预扣记录的持久化，不能在有未完成订单时清空 Redis。
- **限流与防刷**：未实现用户/IP 维度的限流、验证码或风控。

## 手工验证

```bash
# 1. 使用白名单管理员登录得到的 token 新增秒杀券
curl -X POST http://localhost:8081/voucher/seckill \
  -H "Content-Type: application/json" \
  -H "authorization: 管理员token" \
  -d '{
    "shopId": 1,
    "title": "100元代金券",
    "subTitle": "限时秒杀",
    "rules": "全场通用",
    "payValue": 8000,
    "actualValue": 10000,
    "stock": 100,
    "beginTime": "2026-11-01T00:00:00",
    "endTime": "2026-11-30T23:59:59"
  }'

# 2. 登录拿到 token
curl -X POST "http://localhost:8081/user/code?phone=13800138000"
curl -X POST http://localhost:8081/user/login \
  -H "Content-Type: application/json" \
  -d '{"phone":"13800138000","code":"日志中的6位验证码"}'

# 3. 抢购（voucherId 用上一步返回的 id）
curl -X POST http://localhost:8081/voucher-order/seckill/10 \
  -H "authorization: 登录返回的token"

# 4. 重复抢购应返回“不能重复下单”
curl -X POST http://localhost:8081/voucher-order/seckill/10 \
  -H "authorization: 登录返回的token"

# 5. 观察 Redis
redis-cli GET "seckill:stock:10"
redis-cli SMEMBERS "seckill:order:10"
redis-cli XLEN stream.orders
redis-cli XINFO GROUPS stream.orders
redis-cli XPENDING stream.orders g1

# 6. 观察数据库
# SELECT * FROM tb_voucher_order WHERE voucher_id = 10;
# SELECT stock FROM tb_seckill_voucher WHERE voucher_id = 10;
```

消费者会在应用启动时自动创建消费组，不需要手工执行 `XGROUP CREATE`。

## 测试

```bash
mvn test
```

覆盖：

- `VoucherOrderServiceImplTest`：参数校验、Lua 返回码映射、元数据懒加载、库存修复、Redis 异常降级；
- `VoucherOrderPersistenceServiceTest`：订单插入顺序、重复键、数据库库存不足；
- `VoucherOrderConsumerTest`：成功 ACK、重复消息幂等 ACK、失败不 ACK；
- `VoucherServiceImplTest`：秒杀券创建、库存与元数据提交后预热。

默认单元测试使用 Mockito/MockMvc，不连接真实 Redis/MySQL。额外执行真实集成测试：

```bash
mvn -Dhmdp.integration=true test
```

集成测试只在当前 MySQL 连接创建同名临时表以隔离业务数据，连接关闭即删除；Redis 使用随机测试前缀并清理。
覆盖真实事务回滚、重复消息仅扣一次库存、失败任务禁止复活、Lua 重复补偿、重新购买后旧补偿不干扰新订单。
