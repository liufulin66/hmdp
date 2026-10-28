# 通用缓存组件的使用

`CacheClient` 是 Spring 组件，复用现有 `StringRedisTemplate` 和 Hutool，无需新增依赖。
业务层负责参数校验、数据库查询和 `Result` 包装；组件负责空值缓存、互斥重建、随机过期、限流回源和缓存失效。

## 配置业务策略

```java
private static final CachePolicy POLICY = new CachePolicy(
        "shop-detail",               // 稳定的回源分组名称
        Duration.ofMinutes(30),      // 正常结果 TTL
        Duration.ofMinutes(2),       // 不存在或指定空结果的 TTL
        Duration.ofMinutes(5),       // 正常 TTL 增加 0～5 分钟随机量
        Duration.ofSeconds(10),      // 锁租期
        10,                          // 查询缓存/尝试加锁的轮数，包含首次
        Duration.ofMillis(50),       // 锁竞争、删除失败时的重试间隔
        20,                          // 单实例、同一分组的回源并发上限
        3                            // 删除缓存次数，包含首次
);
```

`Duration` 明确时间单位；组件统一按毫秒调用 Redis，支持毫秒级配置。
TTL、锁租期至少为 1 毫秒；随机增量、重试间隔允许为零。

`loadGroup` 必须是业务名称，不能随 ID、分页参数变化。同名分组共享并发额度，不同分组相互隔离。
同名分组配置不同并发上限会抛出配置异常，避免额度取决于首次请求。
分组由组件在实例内保存，因此不要使用每次请求都不同的分组名称。

## 简单主键查询

```java
Shop shop = cacheClient.queryWithMutex(
        "cache:shop:", "lock:shop:", id,
        Shop.class, this::getById, POLICY);
return shop == null ? Result.fail("商户不存在") : Result.ok(shop);
```

泛型 `ID` 保留回调参数类型，可传入 `Long`、`Integer`、`String`、`UUID` 等主键。
`Function<? super ID, ? extends R>` 允许回调接收主键并返回目标类型或其子类。
简单入口按 `prefix + id` 生成 key；前缀必须隔离业务与返回类型。
例如，同一个前缀下的 Long `1` 和 String `"1"` 会产生同一个 key，若它们代表不同业务就必须使用不同前缀。

## 集合和嵌套泛型

```java
TypeReference<List<Shop>> type = new TypeReference<>() { };
List<Shop> shops = cacheClient.queryWithMutex(
        "cache:shops:category:", "lock:shops:category:", categoryId,
        type, this::findByCategory, POLICY);
```

`TypeReference` 来自 `cn.hutool.core.lang.TypeReference`。
匿名子类会保留 `List<Shop>` 的完整类型信息，避免只传 `List.class` 导致元素类型丢失。
`Map<String, List<Shop>>`、`PageResult<Shop>` 等可被 Hutool 编解码的嵌套类型采用相同方式。
必须在调用处写明具体泛型；不要在通用方法内部用 `new TypeReference<R>() {}` 捕获已经被擦除的类型变量。

## 多参数查询与空集合

多参数查询使用完整缓存 key、锁 key 和 `Supplier<R>`。Supplier 在需要回源时执行，捕获业务查询参数。
以下为调用形式，`repository.search` 表示业务自己的查询方法：

```java
String key = "cache:shops:city:" + URLEncoder.encode(city, StandardCharsets.UTF_8)
        + ":type:" + typeId + ":page:" + page + ":size:" + size;

List<Shop> shops = cacheClient.queryWithMutex(
        key, "lock:" + key,
        new TypeReference<List<Shop>>() { },
        () -> repository.search(city, typeId, page, size),
        POLICY,
        List::isEmpty
);
```

key 必须包含所有影响结果的条件，包括分页、排序、租户/用户维度等。
字符串条件应采用稳定、无歧义的编码，不要直接拼接 DTO 的默认 `toString()`。
缓存 key 与锁 key 不能相同，同一组 key 的所有调用方必须使用一致的数据类型与策略。

最后的 `Predicate<? super R>` 是可选的短 TTL 判断器：

- 返回 `null`：始终保存空字符串标记，使用短 TTL；命中后返回 `null`。
- 返回空 List：默认保存 `[]`，使用正常 TTL；命中后仍然是空 List。
- 传入 `List::isEmpty`：空 List 使用短 TTL，但仍保存 `[]`，不会变为 `null`。
- 真实的空字符串：保存 JSON 的 `""`，与 Redis 中表示不存在的零长度字符串区分。

普通 String、数字、布尔、数组同样支持。复杂对象须满足 Hutool 的序列化/反序列化要求；
包含抽象类型或多态字段时，应明确具体返回 DTO。`CacheClientTest` 覆盖实体、日期字段、数组、集合、嵌套 Map 和泛型分页 DTO。

## 修改数据后失效

```java
@Transactional
public Result updateShop(Shop shop) {
    if (!updateById(shop)) {
        return Result.fail("商户不存在");
    }
    cacheClient.evictAfterCommit("cache:shop:", shop.getId(), POLICY);
    return Result.ok();
}
```

存在事务时，在提交成功后同步删除，回滚不会删除；没有事务则立即删除。
复杂查询使用 `evictAfterCommit(fullKey, POLICY)`。
业务层负责确定哪些 key 受到更新影响，组件不会自动推导列表、分页等缓存的关联关系。
删除异常按策略有限重试，失败用日志记录并依赖 TTL，任务不持久化。

## 异常与保护范围

- 锁竞争用尽次数、等待被中断或回源额度不足：抛出 `CacheBusyException`，统一异常处理器返回“查询繁忙，请稍后重试”。不会返回 `null` 冒充不存在。
- Redis 读取或加锁失败：受并发限制地回源，不再尝试写缓存。
- loader 抛出的数据库异常：原样传播，不重试 loader，也不缓存不存在标记；回源许可与已持有的锁会释放。
- 缓存写入失败：记录日志，返回已经成功取得的回源结果。
- 缓存无法还原：按未命中处理，由拿到锁的请求重新加载。

重建锁不自动续期；超过租期可能发生重复回源。并发上限是每实例、每业务分组的额度。
先提交数据库再删除缓存仍然允许并发旧值回填的窗口，组件不提供强一致性保证。
