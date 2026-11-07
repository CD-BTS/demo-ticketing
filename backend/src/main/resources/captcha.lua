-- Challenge verification and grant issuance share the concert hash tag.
local time = redis.call('TIME')
local now = tonumber(time[1])*1000 + math.floor(tonumber(time[2])/1000)
if ARGV[1] == 'create' then
    local expiry = now + tonumber(ARGV[5])
    redis.call('HSET', KEYS[1], 'user', ARGV[2], 'action', ARGV[3], 'answer', ARGV[4], 'expiry', expiry, 'attempts', ARGV[6])
    redis.call('PEXPIRE', KEYS[1], ARGV[5])
    return {tostring(expiry)}
end
local user = redis.call('HGET', KEYS[1], 'user')
if user ~= ARGV[2] then return {'INVALID'} end
local expiry = tonumber(redis.call('HGET', KEYS[1], 'expiry') or '0')
if expiry <= now then redis.call('DEL', KEYS[1]); return {'INVALID'} end
local attempts = redis.call('HINCRBY', KEYS[1], 'attempts', -1)
if attempts < 0 then redis.call('DEL', KEYS[1]); return {'INVALID'} end
if redis.call('HGET', KEYS[1], 'answer') ~= ARGV[3] then
    if attempts == 0 then redis.call('DEL', KEYS[1]) end
    return {'INVALID'}
end
local grantExpiry = now + tonumber(ARGV[4])
redis.call('HSET', KEYS[2], 'user', user, 'action', redis.call('HGET', KEYS[1], 'action'), 'expiry', grantExpiry)
redis.call('PEXPIRE', KEYS[2], ARGV[4])
redis.call('DEL', KEYS[1])
return {'OK', tostring(grantExpiry)}
