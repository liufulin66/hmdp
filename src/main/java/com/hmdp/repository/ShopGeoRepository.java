package com.hmdp.repository;

import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopGeoTask;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;
import java.util.*;

@Repository
public class ShopGeoRepository {
    private static final DefaultRedisScript<Long> APPLY = script("shop-geo-apply.lua",Long.class);
    private static final DefaultRedisScript<Long> BUILD = script("shop-geo-build.lua",Long.class);
    private static final DefaultRedisScript<List> READ = script("shop-geo-read.lua",List.class);
    private final StringRedisTemplate redis;
    private final String prefix;
    @Autowired
    public ShopGeoRepository(StringRedisTemplate redis) { this(redis, RedisConstants.SHOP_GEO_KEY); }
    public ShopGeoRepository(StringRedisTemplate redis, String prefix) { this.redis=redis; this.prefix=prefix; }
    private static <T> DefaultRedisScript<T> script(String path, Class<T> type) {
        var script = new DefaultRedisScript<T>(); script.setLocation(new ClassPathResource(path)); script.setResultType(type); return script;
    }
    public String key(String suffix) { return prefix+suffix; }
    public void apply(ShopGeoTask task) {
        apply(task.getShopId(),task.getVersion(),task.getTypeId(),task.getX(),task.getY(),"");
    }
    public void stage(String token, Shop shop) { apply(shop.getId(),shop.getGeoVersion(),shop.getTypeId(),shop.getX(),shop.getY(),token); }
    private void apply(long id,long version,long type,double x,double y,String token) {
        Long result=redis.execute(APPLY,List.of(),prefix,""+id,""+version,""+type,""+x,""+y,token);
        if (result==null) throw new IllegalStateException("GEO同步结果不明确");
    }
    private boolean build(String action,String token,String argument) {
        return Long.valueOf(1).equals(redis.execute(BUILD,List.of(),action,prefix,token,argument));
    }
    public boolean begin(String token,long lease) { return build("BEGIN",token,""+lease); }
    public boolean renew(String token,long lease) { return build("RENEW",token,""+lease); }
    public void type(String token,long type) { if (!build("TYPE",token,""+type)) throw new IllegalStateException("构建所有权丢失"); }
    public void publish(String token) { if (!build("PUBLISH",token,"")) throw new IllegalStateException("构建所有权丢失"); }
    public void abort(String token) { build("ABORT",token,""); }
    public String status() {
        List<?> value=redis.execute(READ,List.of(),prefix,"","0","0","1","1");
        if (value==null) throw new IllegalStateException("GEO状态读取失败");
        return switch (((Number)value.get(0)).intValue()) { case 1 -> "READY"; case 0 -> "UNINITIALIZED"; default -> "LOST"; };
    }
    public List<Candidate> nearby(long type,double x,double y,int radius,int count) {
        List<?> value=redis.execute(READ,List.of(),prefix,""+type,""+x,""+y,""+radius,""+count);
        if (value==null || ((Number)value.get(0)).intValue()!=1) throw new IllegalStateException("附近索引未就绪或已丢失");
        List<Candidate> result=new ArrayList<>();
        for (Object item:(List<?>)value.get(1)) {
            List<?> row=(List<?>)item;
            result.add(new Candidate(Long.parseLong(row.get(0).toString()),Double.parseDouble(row.get(1).toString())));
        }
        return result;
    }
    public record Candidate(long id,double distance) {}
}
