package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RandomTTL;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(5);

    @Override
    public Result queryById(Long id) {
        // 缓存穿透
        /*Shop shop = cacheClient
                .getWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.SECONDS);
        if (BeanUtil.isEmpty(shop)) {
            return Result.fail("商铺不存在");
        }*/

        // 缓存击穿
        Shop shop = cacheClient.getWithLogicExpire(CACHE_SHOP_KEY, LOCK_SHOP_KEY, id, Shop.class,
                this::getById, 5L, TimeUnit.SECONDS);
        if (BeanUtil.isEmpty(shop)) {
            return Result.fail("商铺不存在");
        }
        return Result.ok(shop);
    }

    public Shop queryWithLogicExpire(Long id) {
        // 缓存击穿 使用逻辑过期时间
        // 1. 从redis中查询缓存
        String key = CACHE_SHOP_KEY + id;

        String dataJson = stringRedisTemplate.opsForValue().get(key);
        // 2. 判断是否命中
        if (StrUtil.isBlank(dataJson)) {
            // 未命中 返回null
            return null;
        }

        // 命中
        // 3. 判断是否逻辑过期
        // 获取数据
        RedisData redisData = JSONUtil.toBean(dataJson, RedisData.class);

        LocalDateTime expireTime = redisData.getExpireTime();
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);

        // 没有逻辑过期 返回数据
        if (expireTime.isAfter(LocalDateTime.now())) {
            return shop;
        }
        // 逻辑过期，修改缓存 返回过期数据

        // 获取互斥锁，开启独立线程
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        // 是否获取成功
        if (isLock) {
            // 获取成功，创建线程，重建缓存
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    this.saveShopToRedis(id, 20L);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(lockKey);
                }
            });
        }

        // 获取失败，返回旧数据
        return shop;
    }

    public Shop queryWithMutex(Long id) {
        // 缓存击穿 互斥锁
        // 1. 从redis中查询缓存
        String key = CACHE_SHOP_KEY + id;

        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2. 判断是否命中
        if (StrUtil.isNotBlank(shopJson)) {
            // 命中 返回数据
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 判断是否为空字符串
        if (shopJson != null) {
            return null;
        }

        // 没有命中 缓存重建
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop;
        try {
            // 1.获取互斥锁
            boolean isLock = tryLock(lockKey);
            if (!isLock) {
                // 获取失败 休眠，重新调用
                Thread.sleep(100);
                return queryWithMutex(id);
            }

            // 2. 获取成功，再次判断缓存是否重建成功
            //  从redis中查询缓存
            shopJson = stringRedisTemplate.opsForValue().get(key);
            //  判断是否命中
            if (StrUtil.isNotBlank(shopJson)) {
                // 命中 返回数据
                return JSONUtil.toBean(shopJson, Shop.class);
            }

            // 判断是否为空字符串
            if (shopJson != null) {
                return null;
            }

            // 缓存没有重建成功，从数据库中查询数据
            shop = getById(id);
            // 模拟重建延时
            Thread.sleep(200);
            // 判断数据是否存在
            if (BeanUtil.isEmpty(shop)) {
                // 不存在 返回错误,并将空值写入redis，防止缓存穿透
                stringRedisTemplate.opsForValue().set(key, "", RandomTTL.getTTL(CACHE_NULL_TTL), TimeUnit.MINUTES);
                return null;
            }

            // 存在
            // 3.将数据写入redis中
            shopJson = JSONUtil.toJsonStr(shop);
            stringRedisTemplate.opsForValue().set(key, shopJson, RandomTTL.getTTL(CACHE_SHOP_TTL), TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 4.释放互斥锁
            unlock(lockKey);
        }

        // 5. 返回数据
        return shop;
    }

    public Shop queryWithPassThrough(Long id) {
        // 缓存穿透解决方案
        // 1. 从redis中查询缓存
        String key = CACHE_SHOP_KEY + id;

        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2. 判断是否命中
        if (StrUtil.isNotBlank(shopJson)) {
            // 命中 返回数据
            return JSONUtil.toBean(shopJson, Shop.class);
        }

        // 判断是否为空字符串
        if (shopJson != null) {
            return null;
        }

        // 没有命中
        // 1.从数据库中查询数据
        Shop shop = getById(id);
        // 2.判断数据是否存在
        if (BeanUtil.isEmpty(shop)) {
            // 不存在 返回错误,并将空值写入redis，防止缓存穿透
            stringRedisTemplate.opsForValue().set(key, "", RandomTTL.getTTL(CACHE_NULL_TTL), TimeUnit.MINUTES);
            return null;
        }

        // 存在
        // 3.将数据写入redis中
        shopJson = JSONUtil.toJsonStr(shop);
        stringRedisTemplate.opsForValue().set(key, shopJson, RandomTTL.getTTL(CACHE_SHOP_TTL), TimeUnit.MINUTES);

        // 4. 返回数据
        return shop;
    }


    public void saveShopToRedis(Long id, Long expireSeconds) throws InterruptedException {
        // 设置逻辑过期时间 缓存击穿解决方案
        Shop shop = getById(id);
        RedisData redisData = new RedisData();
        Thread.sleep(200);
        redisData.setData(shop);
        // 设置逻辑过期时间
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    @Override
    @Transactional
    public Result updateShop(Shop shop) {
        // 更新数据中 确保数据一致性
        Long id = shop.getId();

        if (id == null) {
            return Result.fail("店铺数据有误");
        }

        // 1.更新数据库
        updateById(shop);

        // 2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());

        return Result.ok(shop);
    }


    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

}
