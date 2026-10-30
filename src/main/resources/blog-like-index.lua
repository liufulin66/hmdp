-- 升级兼容：分轮 HSCAN 旧 Hash，补上用户版本和索引；完成前不消费，避免漏掉旧记录。
local pendingType = redis.call('type', KEYS[4]).ok
if pendingType ~= 'none' and pendingType ~= 'zset' then return redis.error_reply('invalid pending type') end
if redis.call('hget', KEYS[1], '_indexed') == '1' then return 1 end
local head = redis.call('hget', KEYS[1], '_version')
if not head then return redis.error_reply('like state is missing') end
local cursor = redis.call('hget', KEYS[1], '_index_cursor') or '0'
local page = redis.call('hscan', KEYS[2], cursor, 'COUNT', ARGV[1])
for i = 1, #page[2], 2 do
    local userId = page[2][i]
    local value = page[2][i + 1]
    local version, liked = string.match(value, '^(%d+):([01])$')
    if value == '0' or value == '1' then
        version = head
        redis.call('hset', KEYS[2], userId, version .. ':' .. value)
    elseif not version then return redis.error_reply('invalid legacy user like state') end
    redis.call('zadd', KEYS[4], 'NX', version, userId)
end
redis.call('hset', KEYS[1], '_index_cursor', page[1])
if page[1] == '0' then
    redis.call('hset', KEYS[1], '_indexed', '1')
    redis.call('hdel', KEYS[1], '_index_cursor')
    return 1
end
return 0
