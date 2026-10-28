# 全局唯一 ID 生成器

`GlobalIdGenerator` 复用项目已有 MyBatis-Plus 3.5.16 的 `DefaultIdentifierGenerator`，
本地生成雪花 ID，无需新增依赖，发号不访问 Redis 或数据库。

它通过 Spring Bean 单例暴露，同时实现 MyBatis-Plus 的 `IdentifierGenerator` 接口。
MyBatis-Plus 自动配置识别这个 Bean，业务提前发号和 ORM 的 `ASSIGN_ID` 自动发号共用同一序列。
不同业务也使用同一个发号入口，无需按业务建立计数器。

## 配置节点编号

```yaml
hmdp:
  id-generator:
    worker-id: ${HMDP_ID_WORKER_ID:1}
    datacenter-id: ${HMDP_ID_DATACENTER_ID:1}
```

默认 `1/1` 用于当前本机单实例开发。两个编号均必须在 `0～31` 范围内，超出范围会启动失败。
显式配置节点编号后，不依赖运行环境自动推断节点号。

多实例示例：

| 实例 | 数据中心编号 | 工作节点编号 |
|---|---|---|
| 实例 A | 1 | 1 |
| 实例 B | 1 | 2 |
| 实例 C | 1 | 3 |

PowerShell 启动示例：

```powershell
$env:HMDP_ID_DATACENTER_ID = "1"
$env:HMDP_ID_WORKER_ID = "2"
mvn spring-boot:run
```

唯一性依赖于所有发号实例使用不冲突的节点编号组合、兼容的算法和时间基准，以及正确的时钟管理。
不要在不同实例上都使用默认编号，也不要为不同业务或每次请求重新创建生成器。
节点号由部署配置负责分配，本组件未引入自动分配节点号的注册中心。

## 秒杀前提前生成订单号

在 Spring 管理的业务类中注入：

```java
@Resource
private GlobalIdGenerator globalIdGenerator;
```

然后在 Redis 预占或发送订单消息之前调用：

```java
long orderId = globalIdGenerator.nextId();

VoucherOrder order = new VoucherOrder();
order.setId(orderId);
order.setUserId(userId);
order.setVoucherId(voucherId);
```

当前 `VoucherOrder.id` 保留 `IdType.INPUT`，由应用提前提供编号。
后续的 Redis 预占记录、消息和数据库订单应沿用同一个编号。
消息重试时复用原订单号，不能每次消费重新发号。

## MyBatis-Plus 自动分配主键

需要在插入时自动分配主键的实体可以使用：

```java
@TableId(type = IdType.ASSIGN_ID)
private Long id;
```

主键为 null 时，MyBatis-Plus 调用同一个 `GlobalIdGenerator` 分配编号；已有编号不会被覆盖。
该数值 ID 也支持 `ASSIGN_ID` 的 String 主键转换；使用数值类型时应使用 Long，Integer 无法容纳雪花 ID。
`IdType.AUTO` 仍由数据库分配自增主键，`IdType.INPUT` 仍由调用方提供。
本次没有修改现有实体的主键策略或数据库表结构，也没有实现秒杀下单流程。

## 字符串编号与前端精度

```java
String id = globalIdGenerator.nextIdString();
```

雪花 ID 通常超出 JavaScript Number 的安全整数范围。返回前端时应使用字符串字段或明确配置为字符串序列化，
前端及后续请求中保持字符串传递，避免通过 Number 转换而丢失精度。
`nextIdString()` 每次生成一个新的 ID 并返回其字符串形式；转换已有订单号使用 `Long.toString(orderId)`。

## 算法与边界

底层使用时间差、数据中心号、工作节点号和毫秒内序列构成 Long ID。
同步与序列耗尽等待、时钟回拨检测均复用 MyBatis-Plus 实现，不另写一套算法。
该版本小幅回拨时尝试等待，未恢复或回拨过大时抛出异常；调用方不应捕获后使用随机数等方式降级替代编号。

MyBatis-Plus 的内存序列不持久化上次发号时间。部署时需要避免节点号重复使用的时间窗口和重启后的时钟回退；
本组件不提供跨进程的节点租约或持久化时间高水位。并发测试不等于对所有故障场景的绝对唯一性证明。

ID 不要求连续，也不承诺多个实例的请求完成顺序与编号大小严格一致。
订单主键唯一约束仍需保留；“一人一券”需要另外的 `(user_id, voucher_id)` 唯一约束。

## 验证范围

- 单实例正数、递增与节点位解析，以及合法范围校验。
- 不同节点之间的 ID 不重复。
- 8 个线程混合使用业务与 ORM 入口，生成 16,000 个 ID 无重复。
- Spring 单例、配置绑定、实际 YAML 环境变量占位符解析及非法配置拒绝启动。
- 实际 MyBatis-Plus 自动配置选中同一 Bean，插入前自动填充 Long/String 主键，保留预设主键及 INPUT/AUTO 语义。

集成测试只运行配置和插入前的主键填充，不执行 SQL，不连接 Redis 或真实数据库。

参考：[MyBatis-Plus 默认生成器源码](https://github.com/baomidou/mybatis-plus/blob/v3.5.16/mybatis-plus-core/src/main/java/com/baomidou/mybatisplus/core/incrementer/DefaultIdentifierGenerator.java)、
[雪花 Sequence 源码](https://github.com/baomidou/mybatis-plus/blob/v3.5.16/mybatis-plus-core/src/main/java/com/baomidou/mybatisplus/core/toolkit/Sequence.java)。
