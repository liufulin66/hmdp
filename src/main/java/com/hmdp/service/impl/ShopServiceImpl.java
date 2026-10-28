package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;

import com.hmdp.utils.CacheClient;
import com.hmdp.utils.CachePolicy;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    private static final CachePolicy SHOP_CACHE_POLICY = new CachePolicy(
            "shop-detail", Duration.ofMinutes(CACHE_SHOP_TTL), Duration.ofMinutes(CACHE_NULL_TTL),
            Duration.ofSeconds(CACHE_SHOP_JITTER_SECONDS),
            SHOP_LOCK_MAX_ATTEMPTS, Duration.ofMillis(SHOP_RETRY_DELAY_MILLIS),
            SHOP_MAX_CONCURRENT_LOADS, SHOP_CACHE_DELETE_MAX_ATTEMPTS);

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryShopById(Long id) {
        if (id == null || id <= 0) {
            return Result.fail("商户ID必须为正数");
        }
        Shop shop = cacheClient.queryWithMutex(
                CACHE_SHOP_KEY, LOCK_SHOP_KEY, id, Shop.class, this::getById, SHOP_CACHE_POLICY);
        return shop == null ? Result.fail("商户不存在") : Result.ok(shop);
    }

    @Override
    @Transactional
    public Result updateShop(Shop shop) {
        if (shop == null || shop.getId() == null || shop.getId() <= 0) {
            return Result.fail("商户ID必须为正数");
        }
        if (!updateById(shop)) {
            return Result.fail("商户不存在");
        }
        cacheClient.evictAfterCommit(CACHE_SHOP_KEY, shop.getId(), SHOP_CACHE_POLICY);
        return Result.ok();
    }

    @Override
    @Transactional
    public Result saveShop(Shop shop) {
        if (shop == null) {
            return Result.fail("商户信息不能为空");
        }
        if (!save(shop)) {
            return Result.fail("新增商户失败");
        }
        // 这个 ID 可能曾被查询过，需要清除之前的空值缓存。
        cacheClient.evictAfterCommit(CACHE_SHOP_KEY, shop.getId(), SHOP_CACHE_POLICY);
        return Result.ok(shop.getId());
    }

}
