package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.LOCK_SHOP_TTL;


@Component
public class CacheClient {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(5);

    public void set(String key, Object value, Long time, TimeUnit unit) {
        // 普通存储
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        // 带逻辑过期时间的存储
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R, ID> R getWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        // 缓存穿透
        // 1. 从redis中查询缓存
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2. 判断是否命中
        if (StrUtil.isNotBlank(json)) {
            // 命中 返回数据
            return JSONUtil.toBean(json, type);
        }

        // 判断是否为空字符串
        if (json != null) {
            return null;
        }
        // 没有命中
        // 1.从数据库中查询数据
        R r = dbFallback.apply(id);
        // 2.判断数据是否存在
        if (BeanUtil.isEmpty(r)) {
            // 不存在 返回错误,并将空值写入redis，防止缓存穿透
            stringRedisTemplate.opsForValue().set(key, "", RandomTTL.getTTL(time), unit);
            return null;
        }

        // 存在
        // 3.将数据写入redis中
        json = JSONUtil.toJsonStr(r);
        this.set(key, r, time, unit);

        // 4. 返回数据
        return r;
    }

    public <R, ID> R getWithMutex(String keyPrefix, String lockKeyPrefix, ID id,
                                  Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        // 缓存击穿 互斥锁
        // 1. 从redis中查询缓存
        String key = keyPrefix + id;

        String json = stringRedisTemplate.opsForValue().get(key);
        // 2. 判断是否命中
        if (StrUtil.isNotBlank(json)) {
            // 命中 返回数据
            return JSONUtil.toBean(json, type);
        }
        // 判断是否为空字符串
        if (json != null) {
            return null;
        }

        // 没有命中 缓存重建
        String lockKey = lockKeyPrefix + id;
        R r;
        try {
            // 1.获取互斥锁
            boolean isLock = tryLock(lockKey);
            if (!isLock) {
                // 获取失败 休眠，重新调用
                Thread.sleep(100);
                return getWithMutex(keyPrefix, lockKeyPrefix, id, type, dbFallback, time, unit);
            }

            // 2. 获取成功，再次判断缓存是否重建成功
            //  从redis中查询缓存
            json = stringRedisTemplate.opsForValue().get(key);
            //  判断是否命中
            if (StrUtil.isNotBlank(json)) {
                // 命中 返回数据
                return JSONUtil.toBean(json, type);
            }

            // 判断是否为空字符串
            if (json != null) {
                return null;
            }

            // 缓存没有重建成功，从数据库中查询数据
            r = dbFallback.apply(id);
            // 模拟重建延时
            Thread.sleep(200);
            // 判断数据是否存在
            if (BeanUtil.isEmpty(r)) {
                // 不存在 返回错误,并将空值写入redis，防止缓存穿透
                this.set(key, "", time, unit);
                return null;
            }

            // 存在
            // 3.将数据写入redis中
            this.set(key, r, time, unit);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 4.释放互斥锁
            unlock(lockKey);
        }
        // 5. 返回数据
        return r;
    }


    public <ID, R> R getWithLogicExpire(String keyPrefix, String lockKeyPrefix, ID id,
                                        Class<R> type, Function<ID, R> dbFallback,Long time,TimeUnit unit) {
        // 缓存击穿 使用逻辑过期时间
        // 1. 从redis中查询缓存
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2. 判断是否命中
        if (StrUtil.isBlank(json)) {
            // 未命中 返回null
            return null;
        }
        // 命中
        // 3. 判断是否逻辑过期
        // 获取数据
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);

        LocalDateTime expireTime = redisData.getExpireTime();
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);

        // 没有逻辑过期 返回数据
        if (expireTime.isAfter(LocalDateTime.now())) {
            return r;
        }
        // 逻辑过期，修改缓存 返回过期数据
        // 获取互斥锁，开启独立线程
        String lockKey = lockKeyPrefix + id;
        boolean isLock = tryLock(lockKey);
        // 是否获取成功
        if (isLock) {
            //获取成功，创建线程，重建缓存
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    R rToSave = dbFallback.apply(id);
                    this.setWithLogicalExpire(key,rToSave,time,unit);
                } catch (RuntimeException e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(lockKey);
                }
            });
        }
        // 返回旧数据
        return r;
    }


    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

}
