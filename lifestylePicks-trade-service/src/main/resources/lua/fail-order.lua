local state = redis.call('HGET', KEYS[3], 'state')
if state ~= 'FAILED' and state ~= 'SUCCESS' then
    -- 数据库已经确认库存不足，释放买家资格，并停止继续接收虚假的 Redis 库存。
    redis.call('SREM', KEYS[2], ARGV[2])
    redis.call('SET', KEYS[1], 0)
    redis.call('HMSET', KEYS[3], 'userId', ARGV[2], 'voucherId', ARGV[3], 'state', 'FAILED', 'errorMsg', ARGV[4])
    redis.call('EXPIRE', KEYS[3], 604800)
end
redis.call('ZREM', KEYS[4], ARGV[1])
return 1
