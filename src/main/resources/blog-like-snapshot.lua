if redis.call('type', KEYS[1]).ok ~= 'hash' then return redis.error_reply('like state is missing') end
local total = redis.call('hget', KEYS[1], '_liked')
local version = redis.call('hget', KEYS[1], '_version')
local flushed = redis.call('hget', KEYS[1], '_flushed')
if not total or not version or not flushed then return redis.error_reply('invalid like metadata') end
local limit = tonumber(ARGV[1])
if not limit or limit < 1 or limit > 1000 then return redis.error_reply('invalid user batch limit') end
if redis.call('hlen', KEYS[2]) ~= redis.call('zcard', KEYS[4]) then
    return redis.error_reply('pending user index is incomplete')
end
local users = redis.call('zrange', KEYS[4], 0, limit - 1)
if #users == 0 and tonumber(version) > tonumber(flushed) then
    return redis.error_reply('pending user index is missing')
end
local result = {total, version}
for _, userId in ipairs(users) do
    local value = redis.call('hget', KEYS[2], userId)
    if not value then return redis.error_reply('pending user state is missing') end
    local userVersion, liked = string.match(value, '^(%d+):([01])$')
    if not userVersion then return redis.error_reply('invalid versioned user state') end
    result[#result + 1] = userId
    result[#result + 1] = userVersion
    result[#result + 1] = liked
end
return result
