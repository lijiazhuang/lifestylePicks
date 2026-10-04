-- 不覆盖正在使用的 Redis 库存，仅在库存缺失时从数据库初始化。
if redis.call('EXISTS', KEYS[1]) == 0 then redis.call('SET', KEYS[1], ARGV[1]) end
redis.call('HMSET', KEYS[2], 'begin', ARGV[2], 'end', ARGV[3])
return 1
