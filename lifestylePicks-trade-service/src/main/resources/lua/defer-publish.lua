-- KEYS: 订单状态、outbox、滑动窗口事件、全局告警冷却标记。
-- ARGV: orderId、当前毫秒、基础延时、最大延时、抖动系数、窗口、阈值、冷却时间、事件唯一 ID。
-- 写入前检查类型与计数；Lua 发生运行时错误不会回滚此前的写入。
local statusType = redis.call('TYPE', KEYS[1]).ok
local outboxType = redis.call('TYPE', KEYS[2]).ok
if (statusType ~= 'hash' and statusType ~= 'none') or
   (outboxType ~= 'zset' and outboxType ~= 'none') then
    return redis.error_reply('Invalid order status or outbox key type')
end
local state = redis.call('HGET', KEYS[1], 'state')
if state == 'SUCCESS' or state == 'FAILED' then
    redis.call('ZREM', KEYS[2], ARGV[1])
    return {0, 0, 0, 0, redis.call('ZCARD', KEYS[2])}
end
if state ~= 'PENDING' then
    return redis.error_reply('Order is not PENDING; delivery record retained')
end
local eventsType = redis.call('TYPE', KEYS[3]).ok
local cooldownType = redis.call('TYPE', KEYS[4]).ok
if (eventsType ~= 'zset' and eventsType ~= 'none') or
   (cooldownType ~= 'string' and cooldownType ~= 'none') then
    return redis.error_reply('Invalid publish retry statistics key type')
end
local previous = redis.call('HGET', KEYS[1], 'publishFailCount') or '0'
if not string.match(previous, '^%d+$') or tonumber(previous) > 9007199254740990 then
    return redis.error_reply('Invalid publishFailCount')
end
local failures = tonumber(previous) + 1
local now = tonumber(ARGV[2])
local delay = tonumber(ARGV[3])
local maxDelay = tonumber(ARGV[4])
local jitter = tonumber(ARGV[5])
local window = tonumber(ARGV[6])
local threshold = tonumber(ARGV[7])
local cooldown = tonumber(ARGV[8])
if not now or not delay or delay <= 0 or not maxDelay or maxDelay < delay or
   not jitter or jitter < 0.8 or jitter > 1 or not window or window <= 0 or
   not threshold or threshold < 1 or not cooldown or cooldown <= 0 or not ARGV[9] then
    return redis.error_reply('Invalid retry scheduling arguments')
end
-- 到达上限即停止翻倍，避免高次数时计算指数溢出。
local exponent = failures - 1
while exponent > 0 and delay < maxDelay do
    delay = math.min(delay * 2, maxDelay)
    exponent = exponent - 1
end
delay = math.max(1, math.floor(delay * jitter))
local nextRetryAt = now + delay
redis.call('HINCRBY', KEYS[1], 'publishFailCount', 1)
redis.call('ZADD', KEYS[2], nextRetryAt, ARGV[1])
redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', now - window)
-- 首次投递失败不计入“补发失败”；相同订单每次失败也有独立事件。
if failures > 1 then
    redis.call('ZADD', KEYS[3], now, ARGV[9])
end
redis.call('PEXPIRE', KEYS[3], window)
local windowFailures = redis.call('ZCARD', KEYS[3])
local alert = 0
if failures > 1 and windowFailures >= threshold then
    if redis.call('SET', KEYS[4], ARGV[1], 'NX', 'PX', cooldown) then
        alert = 1
    end
end
return {failures, nextRetryAt, windowFailures, alert, redis.call('ZCARD', KEYS[2])}
