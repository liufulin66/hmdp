package com.hmdp.service.impl;

import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopGeoTask;
import com.hmdp.mapper.ShopGeoTaskMapper;
import com.hmdp.repository.ShopGeoRepository;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.UUID;

@Slf4j
@Service
public class ShopGeoSyncService {
    @Resource private ShopGeoTaskMapper tasks;
    @Resource private ShopGeoRepository geo;

    /** 调用方的商铺事务内创建任务；不能将提交后的回调当作可靠投递本身。 */
    public long record(Shop shop) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("GEO任务必须与商铺在同一事务创建");
        ShopGeoTask task=new ShopGeoTask();
        task.setShopId(shop.getId()); task.setVersion(shop.getGeoVersion()); task.setTypeId(shop.getTypeId());
        task.setX(shop.getX()); task.setY(shop.getY()); task.setNextAttemptAt(System.currentTimeMillis());
        if (tasks.insertTask(task)!=1) throw new IllegalStateException("创建GEO同步任务失败");
        return task.getId();
    }

    /** afterCommit 调用此代理方法，挂起尚未清理的原事务资源。 */
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public void deliver(long id) {
        try { ShopGeoTask task=tasks.find(id); if (task!=null) attempt(task); }
        catch (RuntimeException e) { log.error("商铺已保存，GEO任务 {} 保留待重试",id,e); }
    }
    @Scheduled(fixedDelayString="${hmdp.shop-geo.retry-interval-ms:1000}")
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public void retry() {
        try {
            long now=System.currentTimeMillis(); tasks.exhaust(now,5,100);
            for (ShopGeoTask task:tasks.due(now,5,100)) attempt(task);
        } catch (RuntimeException e) { log.error("GEO同步扫描失败，下一轮重试",e); }
    }
    private void attempt(ShopGeoTask task) {
        long now=System.currentTimeMillis(); String token=UUID.randomUUID().toString().replace("-","");
        if (tasks.claim(task.getId(),task.getAttempts(),5,token,now,now+60000)!=1) return;
        int attempt=task.getAttempts()+1;
        try { geo.apply(task); tasks.succeed(task.getId(),token); }
        catch (RuntimeException e) {
            try { tasks.fail(task.getId(),token,attempt>=5 ? "FAILED":"PENDING",System.currentTimeMillis()+delay(attempt),error(e)); }
            catch (RuntimeException recording) { log.error("GEO任务 {} 确认失败，等待租约恢复",task.getId(),recording); }
            log.warn("GEO任务 {} 第 {} 次失败，数据库变更已提交",task.getId(),attempt,e);
        }
    }
    private long delay(int attempt) { return switch(attempt) { case 1 -> 1000; case 2 -> 5000; case 3 -> 30000; default -> 120000; }; }
    static String error(Throwable e) { String text=e.getClass().getSimpleName()+": "+String.valueOf(e.getMessage()); return text.substring(0,Math.min(1000,text.length())); }
}
