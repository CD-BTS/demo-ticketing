-- Challenge verification and grant issuance share the concert hash tag.
local time = redis.call('TIME')
local now = tonumber(time[1])*1000 + math.floor(tonumber(time[2])/1000)
if ARGV[1] == 'create' then
    local expiry = now + tonumber(ARGV[5])
    redis.call('HSET', KEYS[1], 'user', ARGV[2], 'action', ARGV[3], 'answer', ARGV[4], 'expiry', expiry, 'attempts', ARGV[6])
    redis.call('PEXPIRE', KEYS[1], ARGV[5])
    return {tostring(expiry)}
end
-- Replay is scoped to concert/user/transition and bound to the exact input.
if KEYS[3] and redis.call('EXISTS', KEYS[3]) == 1 then
    if redis.call('HGET', KEYS[3], 'fingerprint') ~= ARGV[5] then return {'CONFLICT'} end
    return {redis.call('HGET', KEYS[3], 'result'), redis.call('HGET', KEYS[3], 'expiry'), redis.call('HGET', KEYS[3], 'token')}
end
local function remember(result, expiry, token)
    if KEYS[3] then
        redis.call('HSET', KEYS[3], 'fingerprint', ARGV[5], 'result', result, 'expiry', expiry, 'token', token)
        redis.call('PEXPIRE', KEYS[3], ARGV[4])
    end
    return {result, tostring(expiry), token}
end
local user = redis.call('HGET', KEYS[1], 'user')
if user ~= ARGV[2] then return remember('INVALID', 0, '') end
local expiry = tonumber(redis.call('HGET', KEYS[1], 'expiry') or '0')
if expiry <= now then redis.call('DEL', KEYS[1]); return remember('INVALID', 0, '') end
local attempts = redis.call('HINCRBY', KEYS[1], 'attempts', -1)
if attempts < 0 then redis.call('DEL', KEYS[1]); return remember('INVALID', 0, '') end
if redis.call('HGET', KEYS[1], 'answer') ~= ARGV[3] then
    if attempts == 0 then redis.call('DEL', KEYS[1]) end
    return remember('INVALID', 0, '')
end
local grantExpiry = now + tonumber(ARGV[4])
redis.call('HSET', KEYS[2], 'user', user, 'action', redis.call('HGET', KEYS[1], 'action'), 'expiry', grantExpiry)
redis.call('PEXPIRE', KEYS[2], ARGV[4])
redis.call('DEL', KEYS[1])
return remember('OK', grantExpiry, ARGV[6] or '')
