-- KEYS: stock, purchased users, user->order reservation, order result
-- ARGV: userId, orderId, restoreQualification (1/0)
local function kind(key)
    return redis.call('type', key).ok
end
if kind(KEYS[4]) ~= 'hash' then return -1 end
if redis.call('hget', KEYS[4], 'userId') ~= ARGV[1]
        or redis.call('hget', KEYS[4], 'orderId') ~= ARGV[2] then return -1 end
if redis.call('hget', KEYS[4], 'compensated') == '1' then return 1 end
if kind(KEYS[1]) ~= 'string' or kind(KEYS[2]) ~= 'set'
        or kind(KEYS[3]) ~= 'hash' then return -1 end
local stock = tonumber(redis.call('get', KEYS[1]))
if stock == nil or stock < 0 or stock >= 2147483647 or stock ~= math.floor(stock) then return -1 end
if redis.call('hget', KEYS[3], ARGV[1]) ~= ARGV[2] then return -1 end
-- 加库存、移除本单资格、标记完成不可被其他请求穿插。
redis.call('incrby', KEYS[1], 1)
if ARGV[3] == '1' then redis.call('srem', KEYS[2], ARGV[1]) end
redis.call('hdel', KEYS[3], ARGV[1])
redis.call('hset', KEYS[4], 'status', 'FAILED', 'compensated', '1')
return 1
