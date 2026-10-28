# 秒杀下单模块

## 目标

- 不超卖：Redis Lua 原子扣减 + 数据库条件更新双保险；
- 一人一单：Redis Set 去重 + `(user_id, voucher_id)` 唯一索引兜底；
- 请求快：请求线程只访问 Redis，数据库写入由消费者异步完成；
- 可恢复：Redis Stream 消费组 + ACK + pending 重试 + 死信 Stream。

## 请求链路

```
POST /voucher-order/seckill/{voucherId}
  → 登录拦截器（/voucher-order/** 未被排除）
  → VoucherOrderServiceImpl.createVoucherOrder
      ├─ ensureMeta：Redis 元数据缺失时从数据库补齐，并修复缺失库存
      ├─ GlobalIdGenerator.nextId()：提前生成雪花订单号
      └─ 执行 seckill.lua：
           时间窗口 → 库存 → 一人一单 → 扣库存 → 记录用户 → XADD
  → 立即返回订单号字符串（失败时返回售罄/重复/未开始等提示）
        ↓ 异步
  VoucherOrderConsumer（SmartLifecycle，单线程）
      ├─ XREADGROUP 读取 stream.orders
      ├─ VoucherOrderPersistenceService.persistVoucherOrder（@Transactional）
      │     ├─ INSERT tb_voucher_order（唯一索引拦截重复消息）
      │     └─ UPDATE tb_seckill_voucher SET stock = stock - 1 WHERE stock > 0
      └─ XACK
```

## Redis key 与常量

| 用途 | Key | 说明 |
|---|---|---|
| 秒杀库存 | `seckill:stock:{voucherId}` | 活动期间不设过期时间 |
| 已购用户 | `seckill:order:{voucherId}` | Set，`SISMEMBER` O(1) 去重 |
| 活动元数据 | `seckill:meta:{voucherId}` | Hash：`beginTime`、`endTime`，毫秒时间戳 |
| 订单消息 | `stream.orders` | 全局 Stream，消费组 `g1` |
| 死信消息 | `stream.orders.dead` | 超过重试次数的消息，供人工处理 |

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
- **重复消息**：`INSERT` 触发唯一键冲突，说明订单已存在；捕获 `DuplicateKeyException` 后直接 `XACK`，不重复扣库存。
- **临时失败**：不 `XACK`，消息留在 pending；消费者每 30 秒重试一次 pending。
- **重试上限**：同一消息失败 3 次后写入 `stream.orders.dead` 并 `XACK`，避免永久阻塞。
- **ACK 失败**：消息仍会重投，重复投递由唯一索引保证幂等。

## 为什么这样设计

1. **Lua 脚本**：Redis 单线程执行脚本，`检查库存 → 检查重复 → 扣减 → 记录 → 发消息` 不会被其他请求穿插，避免 check-then-act 竞态。
2. **Stream 而不是 `@Async` / List**：Stream 有消费组、ACK、pending 列表，消费者崩溃后可恢复；`@Async` 消息在内存里，进程一挂就丢；`BRPOP` 弹出即删除，没有 ACK。
3. **先插订单再扣数据库库存**：重复消息在第一次写入时就被唯一索引拦下，不会多扣库存；库存条件更新是 Redis 与数据库不一致时的最后防线。
4. **事务方法独立成 Bean**：消费者通过 Spring 代理调用 `VoucherOrderPersistenceService`，`@Transactional` 才会生效；同类自调用会绕过代理。
5. **雪花 ID 在 Lua 之前生成**：Redis 预占、Stream 消息、数据库订单共用同一个编号，重试时不会重新发号。

## 已知限制与运维注意

- **单实例消费者名**：当前固定为 `consumer-1`。多实例部署时应为每个实例配置唯一消费者名，并用 `XPENDING` + `XCLAIM`/`XAUTOCLAIM` 认领其他消费者的 pending 消息。
- **时钟同步**：活动时间比较使用应用服务器时间，所有实例应启用 NTP；否则可能出现提前/延迟开抢。
- **Redis Cluster**：脚本访问的库存/订单/元数据 key 与全局 `stream.orders` 可能不在同一 slot，当前实现面向单机 Redis。上集群需使用哈希标签并把 Stream 按券分片。
- **Stream 长度**：为避免裁剪掉尚未 ACK 的消息，`XADD` 未设置 `MAXLEN`。生产环境应监控 `XLEN` 并在确认无 pending 后执行 `XTRIM`。
- **活动数据清理**：`seckill:stock:*`、`seckill:order:*`、`seckill:meta:*` 在活动期间不能过期；活动结束后应人工或定时清理，避免 Redis 内存持续增长。
- **活动时间修改**：直接修改 `tb_seckill_voucher` 的起止时间后，需要重新写入 `seckill:meta:{voucherId}`，否则脚本仍使用旧时间。
- **活动状态**：当前只校验时间、库存和一人一单，不校验 `tb_voucher.status`；下架券如果仍保留库存和元数据，仍可能被抢购。
- **一致性补偿**：Redis 预占成功但数据库库存耗尽时，消息会重试后进入死信 Stream，需要人工对账/补偿；没有自动恢复 Redis 库存的逻辑。
- **限流与防刷**：未实现用户/IP 维度的限流、验证码或风控。

## 手工验证

```bash
# 1. 新增秒杀券（登录鉴权未开启时可直接调用）
curl -X POST http://localhost:8081/voucher/seckill \
  -H "Content-Type: application/json" \
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

单元测试使用 Mockito 模拟 Redis 和 Mapper，不连接真实 Redis/MySQL；端到端验证请按上面的手工步骤执行。
