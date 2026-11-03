-- 校验先于写入；Lua 中途失败不会回滚已经成功的命令。
redis.replicate_commands()
if #KEYS ~= 1 or #ARGV ~= 2 then return redis.error_reply('invalid UV arguments') end
local visitor, expiration = ARGV[1], ARGV[2]
local hex = '[0-9a-f]'
local uuid = '^'..string.rep(hex,8)..'%-'..string.rep(hex,4)..'%-4'..string.rep(hex,3)..'%-[89ab]'..string.rep(hex,3)..'%-'..string.rep(hex,12)..'$'
if #visitor ~= 36 or not string.match(visitor, uuid) then return redis.error_reply('invalid visitor') end
if #expiration > 15 or not string.match(expiration, '^%d+$') then return redis.error_reply('invalid expiry') end
local expiresAt = tonumber(expiration)
local now = tonumber(redis.call('TIME')[1])
if not expiresAt or expiresAt <= now then return redis.error_reply('expiry is not in future') end
local kind = redis.call('TYPE', KEYS[1]).ok
if kind ~= 'none' and kind ~= 'string' then return redis.error_reply('invalid UV key type') end
if kind == 'string' then
    local count = redis.pcall('PFCOUNT', KEYS[1])
    if type(count) == 'table' and count.err then return redis.error_reply('invalid HyperLogLog') end
end
redis.call('PFADD', KEYS[1], visitor)
redis.call('EXPIREAT', KEYS[1], expiration)
-- 只表示采集命令完成，不把 PFADD 的 0/1 当作精确新访客判断。
return 1
