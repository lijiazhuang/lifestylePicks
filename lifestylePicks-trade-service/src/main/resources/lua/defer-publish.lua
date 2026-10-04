if redis.call('HGET', KEYS[1], 'state') == 'PENDING' then
    redis.call('ZADD', KEYS[2], ARGV[2], ARGV[1])
else
    redis.call('ZREM', KEYS[2], ARGV[1])
end
return 1
