-- ARGV: blogId、userId、目标状态(1/0)、当前毫秒时间。
local function keyType(key) return redis.call('type', key).ok end
if keyType(KEYS[1]) ~= 'hash' then return redis.error_reply('like state is not initialized') end
local changesType = keyType(KEYS[2])
local dirtyType = keyType(KEYS[3])
local pendingType = keyType(KEYS[4])
if changesType ~= 'none' and changesType ~= 'hash' then return redis.error_reply('invalid changes type') end
if dirtyType ~= 'none' and dirtyType ~= 'zset' then return redis.error_reply('invalid dirty type') end
if pendingType ~= 'none' and pendingType ~= 'zset' then return redis.error_reply('invalid pending type') end
local total = tonumber(redis.call('hget', KEYS[1], '_liked'))
local version = tonumber(redis.call('hget', KEYS[1], '_version'))
local flushed = tonumber(redis.call('hget', KEYS[1], '_flushed'))
if not total or not version or not flushed then return redis.error_reply('invalid like metadata') end
if version > flushed and redis.call('hlen', KEYS[2]) == 0 then
    return redis.error_reply('pending like changes are missing')
end
if redis.call('hget', KEYS[1], '_indexed') == '1' and
        redis.call('hlen', KEYS[2]) ~= redis.call('zcard', KEYS[4]) then
    return redis.error_reply('pending user index is incomplete')
end
local field = 'u:' .. ARGV[2]
local before = redis.call('hexists', KEYS[1], field)
local wanted = tonumber(ARGV[3])
if wanted ~= 0 and wanted ~= 1 then return redis.error_reply('invalid like operation') end
-- 榜单只是可重建索引，不能用它决定用户是否已点赞。
local rankType = keyType(KEYS[5])
local rankReady = redis.call('hget', KEYS[1], '_rank_ready') == '1'
if rankType ~= 'none' and rankType ~= 'zset' then
    redis.call('unlink', KEYS[5])
    rankReady = false
elseif rankReady and tonumber(redis.call('hget', KEYS[1], '_rank_size')) ~= redis.call('zcard', KEYS[5]) then
    rankReady = false
end
if not rankReady then redis.call('hset', KEYS[1], '_rank_ready', '0') end
if before ~= wanted then
    local nextTotal = total + wanted - before
    if nextTotal < 0 or nextTotal > 2147483647 or version >= 9007199254740991 then
        return redis.error_reply('like counter out of range')
    end
    total = nextTotal
    version = version + 1
    local versionText = string.format('%.0f', version)
    if wanted == 1 then redis.call('hset', KEYS[1], field, 'v:' .. versionText)
    else redis.call('hdel', KEYS[1], field) end
    redis.call('hset', KEYS[1], '_liked', total, '_version', versionText)
    -- 同一个用户在一批内反复操作，只保留最后状态。
    redis.call('hset', KEYS[2], ARGV[2], versionText .. ':' .. ARGV[3])
    redis.call('zadd', KEYS[4], 'NX', version, ARGV[2])
    -- 只有真实状态变更才进入这里；重复 PUT 不更新 score。
    if wanted == 1 then redis.call('zadd', KEYS[5], 'NX', version, ARGV[2])
    else redis.call('zrem', KEYS[5], ARGV[2]) end
    if rankReady then redis.call('hset', KEYS[1], '_rank_size', redis.call('zcard', KEYS[5])) end
    -- 重建期间也同步更新临时榜单，避免初始化快照覆盖刚发生的操作。
    local deadline = tonumber(redis.call('hget', KEYS[1], '_rank_build_until')) or 0
    if redis.call('hget', KEYS[1], '_rank_build_token') and deadline > tonumber(ARGV[4]) then
        local buildType = keyType(KEYS[6])
        if buildType == 'none' or buildType == 'zset' then
            if wanted == 1 then redis.call('zadd', KEYS[6], version, ARGV[2])
            else redis.call('zrem', KEYS[6], ARGV[2]) end
            redis.call('pexpireat', KEYS[6], deadline)
        end
    end
end
if version > flushed then
    redis.call('zadd', KEYS[3], 'NX', ARGV[4], ARGV[1])
end
return {total, wanted}
