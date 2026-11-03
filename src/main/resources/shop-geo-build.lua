local action,root,token = ARGV[1],ARGV[2],ARGV[3]
local stage = root..'build:'..token..':'
if action == 'BEGIN' then
    if not redis.call('SET',root..'owner',token,'NX','PX',ARGV[4]) then return 0 end
    redis.call('HSET',root..'meta','building',token)
    return 1
end
if redis.call('GET',root..'owner') ~= token then return 0 end
if action == 'RENEW' then
    redis.call('PEXPIRE',root..'owner',ARGV[4])
    return 1
elseif action == 'TYPE' then
    if redis.call('SCARD',stage..'catalog') >= 1000 and redis.call('SISMEMBER',stage..'catalog',ARGV[4]) == 0 then
        return redis.error_reply('too many shop types')
    end
    redis.call('SADD',stage..'catalog',ARGV[4])
    redis.call('HSETNX',stage..'counts',ARGV[4],0)
    redis.call('PEXPIRE',stage..'catalog',7200000)
    redis.call('PEXPIRE',stage..'counts',7200000)
    return 1
elseif action == 'ABORT' then
    redis.call('DEL',root..'owner')
    redis.call('HDEL',root..'meta','building')
    return 1
elseif action == 'PUBLISH' then
    local kinds = redis.call('SMEMBERS',stage..'catalog')
    if #kinds == 0 then return redis.error_reply('build catalog missing') end
    local count = 0
    for _,kind in ipairs(kinds) do
        local expected = tonumber(redis.call('HGET',stage..'counts',kind) or '-1')
        if expected ~= redis.call('ZCARD',stage..kind) then return redis.error_reply('build index lost') end
        count = count + expected
    end
    if count ~= redis.call('HLEN',stage..'state') then return redis.error_reply('build state lost') end
    -- 全部检查完成后再发布；Redis 命令错误不会自动回滚 Lua，不能边校验边切换。
    for _,kind in ipairs(redis.call('SMEMBERS',root..'catalog')) do redis.call('DEL',root..kind) end
    for _,kind in ipairs(kinds) do
        if redis.call('EXISTS',stage..kind) == 1 then
            redis.call('RENAME',stage..kind,root..kind)
            redis.call('PERSIST',root..kind)
        else redis.call('DEL',root..kind) end
    end
    for _,suffix in ipairs({'state','counts','catalog'}) do
        redis.call('DEL',root..suffix)
        if redis.call('EXISTS',stage..suffix) == 1 then
            redis.call('RENAME',stage..suffix,root..suffix)
            redis.call('PERSIST',root..suffix)
        end
    end
    redis.call('HSET',root..'meta','ready',1,'total',count,'generation',token)
    redis.call('HDEL',root..'meta','building')
    redis.call('DEL',root..'owner')
    return 1
end
return redis.error_reply('unknown build action')
