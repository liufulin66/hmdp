-- 单机 Redis：版本判断、实际旧类型删除、GEO 写入、构建双写处于同一原子执行中。
local root, id, version, kind, x, y = ARGV[1],ARGV[2],ARGV[3],ARGV[4],ARGV[5],ARGV[6]
local token = ARGV[7]
local function newer(a,b)
    return #a > #b or (#a == #b and a > b)
end
local function apply(prefix, temporary)
    local previous = redis.call('HGET',prefix..'state',id)
    local oldKind = nil
    if previous then
        local oldVersion
        oldVersion,oldKind = string.match(previous,'^(%d+)|(%d+)$')
        if not oldVersion then return redis.error_reply('invalid shop GEO state') end
        if newer(oldVersion,version) or oldVersion == version then return 0 end
    end
    if redis.call('SISMEMBER',prefix..'catalog',kind) == 0 and redis.call('SCARD',prefix..'catalog') >= 1000 then
        return redis.error_reply('too many shop types')
    end
    if oldKind and oldKind ~= kind then
        redis.call('ZREM',prefix..oldKind,id)
        redis.call('HSET',prefix..'counts',oldKind,redis.call('ZCARD',prefix..oldKind))
    end
    redis.call('GEOADD',prefix..kind,x,y,id)
    redis.call('HSET',prefix..'state',id,version..'|'..kind)
    redis.call('SADD',prefix..'catalog',kind)
    redis.call('HSET',prefix..'counts',kind,redis.call('ZCARD',prefix..kind))
    if temporary then
        for _,suffix in ipairs({'state','catalog','counts',kind}) do redis.call('PEXPIRE',prefix..suffix,7200000) end
    end
    return 1
end
if token ~= '' then
    if redis.call('GET',root..'owner') ~= token then return redis.error_reply('build ownership lost') end
    return apply(root..'build:'..token..':',true)
end
-- 即使活动索引未就绪/已丢失，也先衔接正在构建的新索引；任务仍保留重试。
local building = redis.call('GET',root..'owner')
if building then apply(root..'build:'..building..':',true) end
if redis.call('HGET',root..'meta','ready') ~= '1' then return redis.error_reply('shop GEO not initialized') end
local total = tonumber(redis.call('HGET',root..'meta','total') or '-1')
if total < 0 or redis.call('HLEN',root..'state') ~= total then return redis.error_reply('shop GEO state lost') end
-- 在更新前检查涉及的类型；索引丢失时不可用部分重写伪装成完整索引。
local prior = redis.call('HGET',root..'state',id)
local priorKind = prior and string.match(prior,'|(%d+)$') or kind
for _,t in ipairs({priorKind,kind}) do
    if redis.call('SISMEMBER',root..'catalog',t) == 1 then
        local expected = tonumber(redis.call('HGET',root..'counts',t) or '-1')
        if expected ~= redis.call('ZCARD',root..t) then return redis.error_reply('shop GEO index lost') end
    end
end
local changed = apply(root,false)
redis.call('HSET',root..'meta','total',redis.call('HLEN',root..'state'))
return changed
