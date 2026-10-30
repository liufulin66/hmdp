-- ARGV: blogId、快照版本、当前时间、userId、用户版本、状态 ...
-- 只清理快照内版本未再次变化的用户记录。
local function keyType(key) return redis.call('type', key).ok end
local changesType, dirtyType, pendingType = keyType(KEYS[2]), keyType(KEYS[3]), keyType(KEYS[4])
if (changesType ~= 'none' and changesType ~= 'hash') or
        (dirtyType ~= 'none' and dirtyType ~= 'zset') or
        (pendingType ~= 'none' and pendingType ~= 'zset') then return redis.error_reply('invalid like key type') end
local current = tonumber(redis.call('hget', KEYS[1], '_version'))
local committed = tonumber(ARGV[2])
if not current or not committed or committed > current or (#ARGV - 3) % 3 ~= 0 then
    return redis.error_reply('invalid like acknowledgement')
end
local operations = {}
for i = 4, #ARGV, 3 do
    local userId = ARGV[i]
    local actual = redis.call('hget', KEYS[2], userId)
    if actual == ARGV[i + 1] .. ':' .. ARGV[i + 2] then
        operations[#operations + 1] = {userId, false}
    elseif actual then
        local userVersion = string.match(actual, '^(%d+):[01]$')
        if not userVersion then return redis.error_reply('invalid pending user state') end
        operations[#operations + 1] = {userId, userVersion}
    end
end
-- 先校验完整批次，再修改，避免脏类型或坏数据造成脚本中途失败。
for _, operation in ipairs(operations) do
    if operation[2] == false then
        redis.call('hdel', KEYS[2], operation[1])
        redis.call('zrem', KEYS[4], operation[1])
    else
        redis.call('zadd', KEYS[4], operation[2], operation[1])
    end
end
if redis.call('hlen', KEYS[2]) == 0 then
    redis.call('hset', KEYS[1], '_flushed', string.format('%.0f', current))
    redis.call('zrem', KEYS[3], ARGV[1])
    return 1
end
-- +1ms 避免同毫秒分数相同，笔记又按 ID 排回队首。
redis.call('zadd', KEYS[3], tonumber(ARGV[3]) + 1, ARGV[1])
return 0
