-- All keys share a concert hash tag. Redis TIME is the sole expiration clock.
local waiting, deadlines, admitted, expired, sequence = unpack(KEYS)
local operation, user = ARGV[1], ARGV[2]
local capacity, waitTtl, admitTtl, retention = tonumber(ARGV[3]), tonumber(ARGV[4]), tonumber(ARGV[5]), tonumber(ARGV[6])
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
redis.call('ZREMRANGEBYSCORE', expired, '-inf', now)
for _, id in ipairs(redis.call('ZRANGEBYSCORE', deadlines, '-inf', now)) do
    redis.call('ZREM', waiting, id)
    redis.call('ZREM', deadlines, id)
    redis.call('ZADD', expired, now + retention, id)
end
for _, id in ipairs(redis.call('ZRANGEBYSCORE', admitted, '-inf', now)) do
    redis.call('ZREM', admitted, id)
    redis.call('ZADD', expired, now + retention, id)
end
local created = 0
if operation == 'leave' then
    for _, key in ipairs({waiting, deadlines, admitted, expired}) do redis.call('ZREM', key, user) end
elseif operation == 'join' and not redis.call('ZSCORE', waiting, user) and not redis.call('ZSCORE', admitted, user) then
    local order = redis.call('INCR', sequence)
    redis.call('ZADD', waiting, order, user)
    redis.call('ZADD', deadlines, now + waitTtl, user)
    redis.call('ZREM', expired, user)
    created = 1
end
local slots = math.max(0, capacity - redis.call('ZCARD', admitted))
if slots > 0 then
    for _, id in ipairs(redis.call('ZRANGE', waiting, 0, slots - 1)) do
        redis.call('ZREM', waiting, id)
        redis.call('ZREM', deadlines, id)
        redis.call('ZADD', admitted, now + admitTtl, id)
    end
end
-- Idle garbage collection; never expires a live waiting/admitted membership.
local idleTtl = math.max(waitTtl, admitTtl) + retention
for _, key in ipairs(KEYS) do redis.call('PEXPIRE', key, idleTtl) end
local expiry = redis.call('ZSCORE', admitted, user)
if expiry then return {'ADMITTED', '0', expiry, tostring(created)} end
local rank = redis.call('ZRANK', waiting, user)
if rank then return {'WAITING', tostring(rank + 1), redis.call('ZSCORE', deadlines, user), tostring(created)} end
if redis.call('ZSCORE', expired, user) then return {'EXPIRED', '-1', '0', tostring(created)} end
return {'NOT_JOINED', '-1', '0', tostring(created)}
