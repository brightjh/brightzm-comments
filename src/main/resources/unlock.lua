-- 判断是否相同,即解除锁的线程是否为加锁的线程
if (redis.call('GET', KEYS[1]) == ARGV[1]) then
    -- 一致 删除
    return redis.call('DEL', key)
end
return 0