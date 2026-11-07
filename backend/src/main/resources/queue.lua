-- All keys share a concert hash tag. Redis TIME is the sole expiration clock.
local waiting, deadlines, admitted, expired, sequence = unpack(KEYS)
local delayed, grant = KEYS[6], KEYS[7]
local operation, user = ARGV[1], ARGV[2]
local capacity, waitTtl, admitTtl, retention = tonumber(ARGV[3]), tonumber(ARGV[4]), tonumber(ARGV[5]), tonumber(ARGV[6])
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
redis.call('ZREMRANGEBYSCORE', expired, '-inf', now)
for _, id in ipairs(redis.call('ZRANGEBYSCORE', deadlines, '-inf', now)) do
    redis.call('ZREM', waiting, id)
    redis.call('ZREM', delayed, id)
    redis.call('ZREM', deadlines, id)
    redis.call('ZADD', expired, now + retention, id)
end
for _, id in ipairs(redis.call('ZRANGEBYSCORE', admitted, '-inf', now)) do
    redis.call('ZREM', admitted, id)
    redis.call('ZADD', expired, now + retention, id)
end
local created = 0
if operation == 'leave' then
    for _, key in ipairs({waiting, delayed, deadlines, admitted, expired}) do redis.call('ZREM', key, user) end
elseif operation == 'join' and not redis.call('ZSCORE', waiting, user) and not redis.call('ZSCORE', delayed, user) and not redis.call('ZSCORE', admitted, user) then
    -- Recheck active membership before applying policy: concurrent retries cannot
    -- reclassify a user or require another CAPTCHA. Rejected joins never get a sequence.
    if ARGV[10] == '1' then return {'QUEUE_RESTRICTED', '-1', '0', '0'} end
    if ARGV[9] == '1' then
        if ARGV[11] ~= '1' then return {'CAPTCHA_REQUIRED', '-1', '0', '0'} end
        if redis.call('HGET', grant, 'user') ~= user or redis.call('HGET', grant, 'action') ~= ARGV[7]
            or tonumber(redis.call('HGET', grant, 'expiry') or '0') <= now then
            return {'CAPTCHA_INVALID', '-1', '0', '0'}
        end
    end
    local order = redis.call('INCR', sequence)
    local target = ARGV[8] == '1' and delayed or waiting
    redis.call('ZADD', target, order, user)
    redis.call('ZADD', deadlines, now + waitTtl, user)
    redis.call('ZREM', expired, user)
    if ARGV[9] == '1' then redis.call('DEL', grant) end
    created = 1
end
local slots = math.max(0, capacity - redis.call('ZCARD', admitted))
if slots > 0 then
    for _, id in ipairs(redis.call('ZRANGE', waiting, 0, slots - 1)) do
        redis.call('ZREM', waiting, id)
        redis.call('ZREM', deadlines, id)
        redis.call('ZADD', admitted, now + admitTtl, id)
    end
    slots = math.max(0, capacity - redis.call('ZCARD', admitted))
    if slots > 0 then
        for _, id in ipairs(redis.call('ZRANGE', delayed, 0, slots - 1)) do
            redis.call('ZREM', delayed, id)
            redis.call('ZREM', deadlines, id)
            redis.call('ZADD', admitted, now + admitTtl, id)
        end
    end
end
-- Idle garbage collection; never expires a live waiting/admitted membership.
local idleTtl = math.max(waitTtl, admitTtl) + retention
for _, key in ipairs({waiting, deadlines, admitted, expired, sequence, delayed}) do redis.call('PEXPIRE', key, idleTtl) end
local expiry = redis.call('ZSCORE', admitted, user)
if expiry then return {'ADMITTED', '0', expiry, tostring(created)} end
local rank = redis.call('ZRANK', waiting, user)
if rank then return {'WAITING', tostring(rank + 1), redis.call('ZSCORE', deadlines, user), tostring(created)} end
rank = redis.call('ZRANK', delayed, user)
if rank then return {'WAITING', tostring(redis.call('ZCARD', waiting) + rank + 1), redis.call('ZSCORE', deadlines, user), tostring(created)} end
if redis.call('ZSCORE', expired, user) then return {'EXPIRED', '-1', '0', tostring(created)} end
return {'NOT_JOINED', '-1', '0', tostring(created)}
