package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.ShopPage;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.repository.ShopGeoRepository;
import com.hmdp.utils.ShopGeoPolicy;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;

import com.hmdp.utils.CacheClient;
import com.hmdp.utils.CachePolicy;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
@Slf4j
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    private static final CachePolicy SHOP_CACHE_POLICY = new CachePolicy(
            "shop-detail", Duration.ofMinutes(CACHE_SHOP_TTL), Duration.ofMinutes(CACHE_NULL_TTL),
            Duration.ofSeconds(CACHE_SHOP_JITTER_SECONDS),
            SHOP_LOCK_MAX_ATTEMPTS, Duration.ofMillis(SHOP_RETRY_DELAY_MILLIS),
            SHOP_MAX_CONCURRENT_LOADS, SHOP_CACHE_DELETE_MAX_ATTEMPTS);

    @Resource
    private CacheClient cacheClient;

    @Resource private ShopTypeMapper shopTypes;
    @Resource private ShopGeoRepository geo;
    @Resource private ShopGeoSyncService geoSync;

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
        Shop existing=baseMapper.lockById(shop.getId());
        if (existing==null) return Result.fail("商户不存在");
        Shop merged=BeanUtil.copyProperties(existing,Shop.class);
        BeanUtil.copyProperties(shop,merged,CopyOptions.create().ignoreNullValue());
        String invalid=validateLocation(merged);
        if (invalid!=null) return Result.fail(invalid);
        if (!updateById(shop)) {
            return Result.fail("商户不存在");
        }
        if (baseMapper.bumpGeoVersion(shop.getId())!=1) throw new IllegalStateException("商铺版本更新失败");
        recordGeo(baseMapper.selectById(shop.getId()));
        cacheClient.evictAfterCommit(CACHE_SHOP_KEY, shop.getId(), SHOP_CACHE_POLICY);
        return Result.ok();
    }

    @Override
    @Transactional
    public Result saveShop(Shop shop) {
        if (shop == null) {
            return Result.fail("商户信息不能为空");
        }
        String invalid=validateLocation(shop);
        if (invalid!=null) return Result.fail(invalid);
        shop.setGeoVersion(1L);
        if (!save(shop)) {
            return Result.fail("新增商户失败");
        }
        recordGeo(shop);
        // 这个 ID 可能曾被查询过，需要清除之前的空值缓存。
        cacheClient.evictAfterCommit(CACHE_SHOP_KEY, shop.getId(), SHOP_CACHE_POLICY);
        return Result.ok(shop.getId());
    }

    private String validateLocation(Shop shop) {
        if (!ShopGeoPolicy.validCoordinates(shop.getX(),shop.getY())) return "商铺经纬度必须有限且在Redis GEO支持范围内";
        if (shop.getTypeId()==null || shop.getTypeId()<=0 || shopTypes.selectById(shop.getTypeId())==null) return "商铺类型不存在";
        return null;
    }
    private void recordGeo(Shop snapshot) {
        long taskId=geoSync.record(snapshot);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try { geoSync.deliver(taskId); }
                catch (RuntimeException e) { log.error("商铺已提交，GEO任务 {} 等待持久化重试",taskId,e); }
            }
        });
    }

    @Override
    public Result queryShopByType(Long typeId,Integer current,Double x,Double y,Integer radius,boolean pageInfo) {
        if (typeId==null || typeId<=0) return Result.fail("商铺类型ID必须为正数");
        if (current==null || current<1 || current>ShopGeoPolicy.MAX_PAGE) return Result.fail("页码必须在1到20之间");
        if (radius==null || radius<1 || radius>ShopGeoPolicy.MAX_RADIUS) return Result.fail("半径必须在1到20000米之间");
        if ((x==null)!=(y==null)) return Result.fail("经度和纬度必须同时提供");
        if (x!=null && !ShopGeoPolicy.validCoordinates(x,y)) return Result.fail("经纬度必须有限且在Redis GEO支持范围内");
        if (shopTypes.selectById(typeId)==null) return Result.fail("商铺类型不存在");
        int from=(current-1)*ShopGeoPolicy.PAGE_SIZE, end=current*ShopGeoPolicy.PAGE_SIZE;
        ShopPage page;
        if (x==null) {
            List<Shop> rows=baseMapper.byType(typeId,from,ShopGeoPolicy.PAGE_SIZE+1);
            page=new ShopPage(new ArrayList<>(rows.subList(0,Math.min(rows.size(),ShopGeoPolicy.PAGE_SIZE))),
                    current<ShopGeoPolicy.MAX_PAGE && rows.size()>ShopGeoPolicy.PAGE_SIZE);
        } else {
            try {
                var candidates=geo.nearby(typeId,x,y,radius,Math.min(end+1,ShopGeoPolicy.MAX_CANDIDATES));
                var slice=candidates.subList(Math.min(from,candidates.size()),Math.min(end,candidates.size()));
                Map<Long,Shop> details=new HashMap<>();
                if (!slice.isEmpty()) listByIds(slice.stream().map(ShopGeoRepository.Candidate::id).toList())
                        .forEach(shop -> details.put(shop.getId(),shop));
                List<Shop> ordered=new ArrayList<>();
                for (var candidate:slice) {
                    Shop shop=details.get(candidate.id());
                    if (shop!=null && typeId.equals(shop.getTypeId())) ordered.add(shop.setDistance(candidate.distance()));
                }
                page=new ShopPage(ordered,current<ShopGeoPolicy.MAX_PAGE && candidates.size()>end);
            } catch (RuntimeException e) {
                log.warn("类型 {} 附近查询暂不可用",typeId,e);
                return Result.fail("附近商铺暂不可用，请稍后重试或主动切换普通分类");
            }
        }
        return Result.ok(pageInfo ? page:page.list());
    }

}
