--- 秒杀下单原子脚本
--- KEYS[1] 库存 key        seckill:stock:{voucherId}
--- KEYS[2] 已购用户集合     seckill:order:{voucherId}
--- KEYS[3] 活动元数据       seckill:meta:{voucherId}（beginTime/endTime，毫秒时间戳）
--- KEYS[4] 订单消息 Stream  stream.orders
--- ARGV[1] voucherId
--- ARGV[2] userId
--- ARGV[3] orderId
--- ARGV[4] 当前时间（毫秒）
---
--- 返回值：0 成功；1 库存不足；2 重复下单；3 活动未就绪；4 未开始；5 已结束

local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]
local now = tonumber(ARGV[4])
if now == nil then
    return 3
end

-- 活动时间未预热或格式错误时，不能把 nil 和数字比较
local beginTime = tonumber(redis.call('hget', KEYS[3], 'beginTime'))
local endTime = tonumber(redis.call('hget', KEYS[3], 'endTime'))
if beginTime == nil or endTime == nil then
    return 3
end
if now < beginTime then
    return 4
end
if now > endTime then
    return 5
end

-- 库存 key 不存在时 tonumber 返回 nil，直接比较会抛 Lua 异常
local stock = tonumber(redis.call('get', KEYS[1]))
if stock == nil then
    return 3
end
if stock <= 0 then
    return 1
end

-- 一人一单：Set 中已存在说明重复下单
if tonumber(redis.call('sismember', KEYS[2], userId)) == 1 then
    return 2
end

-- 扣库存、记录用户、发送订单消息
redis.call('incrby', KEYS[1], -1)
redis.call('sadd', KEYS[2], userId)
redis.call('xadd', KEYS[4], '*', 'userId', userId, 'voucherId', voucherId, 'id', orderId)
return 0
