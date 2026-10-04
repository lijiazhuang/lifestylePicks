redis.call('HMSET', KEYS[1], 'id', ARGV[1], 'nickName', ARGV[2], 'icon', ARGV[3])
redis.call('EXPIRE', KEYS[1], ARGV[4])
return 1
