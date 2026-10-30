-- KEYS 与点赞脚本相同；ARGV: action, blogId, token/limit, now, [userId, version ...]。
local action, token, now = ARGV[1], ARGV[3], tonumber(ARGV[4])
local function keyType(key) return redis.call('type', key).ok end
if keyType(KEYS[1]) ~= 'hash' then
    if action == 'read' or action == 'abort' then return {0} end
    return redis.error_reply('like state is missing')
end
if action == 'read' then
    local limit = tonumber(ARGV[3])
    if not limit or limit < 1 or limit > 100 then return redis.error_reply('invalid ranking limit') end
    local rankType = keyType(KEYS[5])
    if rankType ~= 'none' and rankType ~= 'zset' then return {0} end
    local expected = tonumber(redis.call('hget', KEYS[1], '_rank_size'))
    if redis.call('hget', KEYS[1], '_rank_ready') ~= '1' or
            not expected or redis.call('zcard', KEYS[5]) ~= expected then return {0} end
    local result = {1}
    for _, userId in ipairs(redis.call('zrange', KEYS[5], 0, limit - 1)) do result[#result + 1] = userId end
    return result
end
if action == 'begin' then
    redis.call('unlink', KEYS[6])
    redis.call('hset', KEYS[1], '_rank_ready', '0', '_rank_build_token', token, '_rank_build_until', now + 120000)
    return {1}
end
if redis.call('hget', KEYS[1], '_rank_build_token') ~= token then
    if action == 'abort' then return {0} end
    return redis.error_reply('ranking rebuild ownership changed')
end
if action == 'abort' then
    redis.call('unlink', KEYS[6])
    redis.call('hdel', KEYS[1], '_rank_build_token', '_rank_build_until')
    return {1}
end
local deadline = tonumber(redis.call('hget', KEYS[1], '_rank_build_until')) or 0
if now >= deadline then return redis.error_reply('ranking rebuild timed out') end
local buildType = keyType(KEYS[6])
if buildType ~= 'none' and buildType ~= 'zset' then return redis.error_reply('invalid ranking build type') end
if action == 'seed' then
    if (#ARGV - 4) % 2 ~= 0 or (#ARGV - 4) / 2 > 500 then return redis.error_reply('invalid ranking seed batch') end
    for i = 5, #ARGV, 2 do
        local userId, candidate = ARGV[i], tonumber(ARGV[i + 1])
        if not candidate or candidate < 0 then return redis.error_reply('invalid ranking version') end
        local member = redis.call('hget', KEYS[1], 'u:' .. userId)
        if member then
            -- 以执行这一刻的真实会员状态和版本为准，不能被旧数据库快照覆盖。
            local version = string.match(member, '^v:(%d+)$')
            if not version then
                local pending = redis.call('hget', KEYS[2], userId) or ''
                version = string.match(pending, '^(%d+):1$') or ARGV[i + 1]
                redis.call('hset', KEYS[1], 'u:' .. userId, 'v:' .. version)
            end
            redis.call('zadd', KEYS[6], version, userId)
        else
            redis.call('zrem', KEYS[6], userId)
        end
    end
    redis.call('pexpireat', KEYS[6], deadline)
    return {1}
end
if action == 'publish' then
    if buildType == 'zset' then
        redis.call('rename', KEYS[6], KEYS[5])
        redis.call('persist', KEYS[5])
    else redis.call('unlink', KEYS[5]) end
    redis.call('hset', KEYS[1], '_rank_ready', '1', '_rank_size', redis.call('zcard', KEYS[5]))
    redis.call('hdel', KEYS[1], '_rank_build_token', '_rank_build_until')
    return {1}
end
return redis.error_reply('unknown ranking action')
