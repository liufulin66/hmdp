package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_TTL;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
        boolean cacheAvailable = true;
        try {
            String json = stringRedisTemplate.opsForValue().get(CACHE_SHOP_TYPE_KEY);
            // [] 也是有效命中，避免数据库为空时每次请求都回源。
            if (json != null) {
                return Result.ok(JSONUtil.toList(json, ShopType.class));
            }
        } catch (DataAccessException e) {
            log.warn("商户类型缓存不可用，回源数据库", e);
            cacheAvailable = false;
        }

        List<ShopType> typeList = list(new QueryWrapper<ShopType>().orderByAsc("sort"));
        if (cacheAvailable) {
            long ttlMinutes = typeList.isEmpty() ? CACHE_NULL_TTL : CACHE_SHOP_TYPE_TTL;
            try {
                // 数据和过期时间一次写入，避免留下永不过期的缓存。
                stringRedisTemplate.opsForValue().set(
                        CACHE_SHOP_TYPE_KEY, JSONUtil.toJsonStr(typeList), ttlMinutes, TimeUnit.MINUTES);
            } catch (DataAccessException e) {
                log.warn("写入商户类型缓存失败，返回数据库结果", e);
            }
        }
        return Result.ok(typeList);
    }
}
