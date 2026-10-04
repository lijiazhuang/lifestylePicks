local stockType = redis.call('TYPE', KEYS[1]).ok
local buyersType = redis.call('TYPE', KEYS[2]).ok
local outboxType = redis.call('TYPE', KEYS[3]).ok
local metaType = redis.call('TYPE', KEYS[4]).ok
local statusType = redis.call('TYPE', KEYS[5]).ok
if stockType ~= 'string' or metaType ~= 'hash' or
   (buyersType ~= 'none' and buyersType ~= 'set') or
   (outboxType ~= 'none' and outboxType ~= 'zset') or statusType ~= 'none' then return 5 end
local stock = tonumber(redis.call('GET', KEYS[1]))
local beginTime = tonumber(redis.call('HGET', KEYS[4], 'begin'))
local endTime = tonumber(redis.call('HGET', KEYS[4], 'end'))
if not stock or not beginTime or not endTime then return 5 end
local now = tonumber(ARGV[4])
if now < beginTime then return 3 end
if now > endTime then return 4 end
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then return 2 end
if stock <= 0 then return 1 end
redis.call('INCRBY', KEYS[1], -1)
redis.call('SADD', KEYS[2], ARGV[1])
redis.call('HMSET', KEYS[5], 'userId', ARGV[1], 'voucherId', ARGV[2], 'state', 'PENDING')
-- 待投递状态不设 TTL，长时间停机后仍能补发到 RocketMQ。
redis.call('ZADD', KEYS[3], ARGV[4], ARGV[3])
return 0
