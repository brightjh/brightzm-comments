package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static com.hmdp.utils.RedisConstants.*;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private CacheClient cacheClient;
    @Resource
    private ShopServiceImpl shopService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    private ExecutorService ex = Executors.newFixedThreadPool(500);
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final DefaultRedisScript<String> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("skillvoucher.lua"));
        SECKILL_SCRIPT.setResultType(String.class);
    }

    @Resource
    private RedissonClient redissonClient;

    @Test
    public void testCacheClient() {
        Shop shop = shopService.getById(1L);
        cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY + 1, shop, 5L, TimeUnit.SECONDS);
    }

    @Test
    public void testRedisIdWorker() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);
        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id = " + id);
            }
            latch.countDown();
        };
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            ex.submit(task);
        }
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("time = " + (end - begin));

    }

    @Test
    public void testVoucher(){
        /*SeckillVoucher voucher = seckillVoucherService.getById(10);
        System.out.println(voucher);*/

    }

    @Test
    public void testRedisson() throws InterruptedException {
        // 获取锁对象
        RLock lock = redissonClient.getLock("anyLock");
        // 尝试获取锁
        boolean isLock = lock.tryLock(1, 10, TimeUnit.SECONDS);
        // 判断是否获取成功
        if (isLock) {
            try {
                System.out.println("锁获取成功");
            }finally {
                lock.unlock();
            }
        }
    }

    @Test
    public void testLua(){
        List<String> keys = new ArrayList<>();
        // 优惠券剩余数量 key
        keys.add(SECKILL_STOCK_KEY + 11);

        // 执行lua脚本
        String result = stringRedisTemplate.execute(SECKILL_SCRIPT,keys);
        System.out.println(result);
    }


}
