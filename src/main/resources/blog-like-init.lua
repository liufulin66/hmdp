-- KEYS: 状态 hash、待落库用户 hash、待同步笔记 zset、待落库用户索引 zset。
-- ARGV: blogId、数据库总数、数据库版本、已点赞 userId ...
local function keyType(key) return redis.call('type', key).ok end
if keyType(KEYS[1]) ~= 'none' then
    if keyType(KEYS[1]) ~= 'hash' then return redis.error_reply('invalid like state type') end
    return 0
end
local dirtyType = keyType(KEYS[3])
if dirtyType ~= 'none' and dirtyType ~= 'zset' then return redis.error_reply('invalid dirty type') end
-- 有未处理记录时不能从滞后的数据库盲目覆盖缓存。
if redis.call('exists', KEYS[2]) == 1 or redis.call('exists', KEYS[4]) == 1 or redis.call('zscore', KEYS[3], ARGV[1]) then
    return redis.error_reply('like state missing while changes are pending')
end
redis.call('hset', KEYS[1], '_liked', ARGV[2], '_version', ARGV[3], '_flushed', ARGV[3], '_indexed', '1', '_rank_ready', '0')
for i = 4, #ARGV do redis.call('hset', KEYS[1], 'u:' .. ARGV[i], '1') end
return 1
