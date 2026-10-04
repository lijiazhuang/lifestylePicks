redis.call('HMSET', KEYS[1], 'userId', ARGV[2], 'voucherId', ARGV[3], 'state', 'SUCCESS', 'canonicalId', ARGV[4])
redis.call('EXPIRE', KEYS[1], 604800)
redis.call('ZREM', KEYS[2], ARGV[1])
return 1
