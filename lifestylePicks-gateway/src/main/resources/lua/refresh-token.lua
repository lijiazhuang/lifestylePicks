-- 只读取现有登录 Hash；用户有效时原子续期，不创建或复活已失效 Token。
local userId = redis.call('HGET', KEYS[1], 'id')
if not userId or not string.match(userId, '^[1-9]%d*$') then
    return nil
end

redis.call('EXPIRE', KEYS[1], ARGV[1])
return userId
