-- 获取key
local stockKey = KEYS[1]
local orderKey = KEYS[2]
-- 获取各个ID
local userId = ARGV[1]
local voucherId = ARGV[2]
local orderId = ARGV[3]

-- 判断库存
if (tonumber(redis.call("GET", stockKey))) <= 0 then
    return 1;
end
-- 判断用户是否重复下单
if (redis.call("SISMEMBER", orderKey, userId) >= 1) then
    return 2
end
-- 减少库存
redis.call("INCRBY", stockKey, -1)
-- 写入用户
redis.call("SADD", orderKey, userId)
-- 发送消息到消息队列
redis.call("XADD", "stream.orders", "*", 'userID', userId, "voucherId", voucherId, "id", orderId)
return 0