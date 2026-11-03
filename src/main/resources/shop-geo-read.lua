local root,kind = ARGV[1],ARGV[2]
if redis.call('HGET',root..'meta','ready') ~= '1' then
    if redis.call('EXISTS',root..'state',root..'counts',root..'catalog') > 0 then return {-1,{}} end
    return {0,{}}
end
local total = tonumber(redis.call('HGET',root..'meta','total') or '-1')
if total < 0 or redis.call('HLEN',root..'state') ~= total or redis.call('EXISTS',root..'catalog') == 0 then return {-1,{}} end
local function valid(t)
    local expected = tonumber(redis.call('HGET',root..'counts',t) or '-1')
    return redis.call('SISMEMBER',root..'catalog',t) == 1 and expected == redis.call('ZCARD',root..t)
end
if kind == '' then
    for _,t in ipairs(redis.call('SMEMBERS',root..'catalog')) do if not valid(t) then return {-1,{}} end end
    return {1,{}}
end
if not valid(kind) then return {-1,{}} end
-- Redis 5 兼容；ASC + COUNT 保持真正的最近 N 个候选，不使用 ANY。
return {1,redis.call('GEORADIUS',root..kind,ARGV[3],ARGV[4],ARGV[5],'m','WITHDIST','ASC','COUNT',ARGV[6])}
