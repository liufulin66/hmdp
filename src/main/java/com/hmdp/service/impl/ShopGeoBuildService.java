package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.*;
import com.hmdp.repository.ShopGeoRepository;
import com.hmdp.utils.ShopGeoPolicy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.util.*;

@Slf4j
@Service
public class ShopGeoBuildService {
    @Resource private ShopMapper shops;
    @Resource private ShopTypeMapper types;
    @Resource private ShopGeoBuildMapper jobs;
    @Resource private ShopGeoRepository geo;

    public Result request() { jobs.ensure(); jobs.queue(System.currentTimeMillis()); return Result.ok(jobs.find()); }
    public Result status() {
        Map<String,Object> result=new LinkedHashMap<>();
        var job=jobs.find();
        try {
            String state=geo.status();
            if (state.equals("UNINITIALIZED") && job!=null && job.getLastSuccessfulGeneration()!=null) state="LOST";
            result.put("index",state);
        } catch (RuntimeException e) { result.put("index","UNAVAILABLE"); }
        result.put("build",job); return Result.ok(result);
    }
    @Scheduled(fixedDelayString="${hmdp.shop-geo.build-interval-ms:1000}")
    public void run() {
        String token=UUID.randomUUID().toString().replace("-",""); long now=System.currentTimeMillis();
        try {
            if (jobs.claim(token,now,now+60000)!=1) return;
            if (!geo.begin(token,60000)) throw new IllegalStateException("其他构建仍持有Redis所有权");
            var catalog=types.selectList(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.hmdp.entity.ShopType>()
                    .orderByAsc("id").last("LIMIT "+(ShopGeoPolicy.MAX_TYPES+1)));
            if (catalog.isEmpty() || catalog.size()>ShopGeoPolicy.MAX_TYPES) throw new IllegalStateException("商铺类型数量必须在1到1000之间");
            Set<Long> allowed=new HashSet<>();
            for (var type:catalog) { allowed.add(type.getId()); geo.type(token,type.getId()); }
            long after=0;
            while (true) {
                renew(token);
                List<Shop> batch=shops.scanGeo(after,150);
                if (batch.isEmpty()) break;
                for (Shop shop:batch) {
                    if (!allowed.contains(shop.getTypeId()) || !ShopGeoPolicy.validCoordinates(shop.getX(),shop.getY())) {
                        throw new IllegalStateException("历史商铺 "+shop.getId()+" 的类型或坐标无效，保留旧索引");
                    }
                    geo.stage(token,shop); after=shop.getId();
                }
            }
            renew(token); geo.publish(token); jobs.finish(token,"SUCCESS",null);
        } catch (RuntimeException e) {
            log.error("GEO构建失败，已就绪索引保留",e);
            try { geo.abort(token); } catch (RuntimeException ignored) { log.warn("GEO构建所有权等待租约过期"); }
            try { jobs.finish(token,"FAILED",ShopGeoSyncService.error(e)); } catch (RuntimeException recording) { log.error("构建失败记录未保存，等待租约恢复",recording); }
        }
    }
    private void renew(String token) {
        long now=System.currentTimeMillis();
        if (jobs.renew(token,now,now+60000)!=1 || !geo.renew(token,60000)) throw new IllegalStateException("GEO构建所有权已过期");
    }
}
