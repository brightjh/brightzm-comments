package com.hmdp.service.impl;

import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryList() {
        List<ShopType> shopTypeList = null;

        // 1.从redis中获取数据
        String shopTypesByRe = stringRedisTemplate.opsForValue().get(CACHE_SHOP_TYPE_KEY);

        // 2.判断数据是否正确
        if (StrUtil.isNotBlank(shopTypesByRe)) {
            shopTypeList = JSONUtil.toList(JSONUtil.parseArray(shopTypesByRe), ShopType.class);
        }

        if (shopTypeList != null && shopTypeList.size() == SHOP_TYPE_COUNT) {
            shopTypeList.sort(Comparator.comparingInt(ShopType::getSort));
            return Result.ok(shopTypeList);
        }

        // 3.redis中不存在数据，从数据库中查询
        List<ShopType> shopTypesByDB = query().orderByAsc("sort").list();

        // 4.将数据存储到redis中
        String shopTypesToRe = JSONUtil.toJsonStr(shopTypesByDB);

        stringRedisTemplate.opsForValue().set(CACHE_SHOP_TYPE_KEY, shopTypesToRe);

        return Result.ok(shopTypesByDB);
    }
}
