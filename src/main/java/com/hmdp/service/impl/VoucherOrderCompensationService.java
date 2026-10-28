package com.hmdp.service.impl;

import com.hmdp.entity.VoucherOrderTask;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

import static com.hmdp.utils.RedisConstants.*;

@Service
public class VoucherOrderCompensationService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private DefaultRedisScript<Long> compensateSeckillScript;
    @Resource
    private VoucherOrderPersistenceService persistenceService;

    public void compensate(VoucherOrderTask task) {
        if (!VoucherOrderTask.COMPENSATING.equals(task.getStatus())) {
            throw new IllegalArgumentException("只能补偿已确认失败的下单任务");
        }
        Long result = stringRedisTemplate.execute(compensateSeckillScript, List.of(
                        SECKILL_STOCK_KEY + task.getVoucherId(),
                        SECKILL_ORDER_KEY + task.getVoucherId(),
                        SECKILL_RESERVATION_KEY + task.getVoucherId(),
                        SECKILL_RESULT_KEY + task.getOrderId()),
                task.getUserId().toString(), task.getOrderId().toString(),
                Boolean.TRUE.equals(task.getRestoreQualification()) ? "1" : "0");
        if (result == null || result != 1L) {
            // 不能证明预扣归属或库存 key 丢失时不盲目加库存，保留 pending 等待恢复。
            throw new IllegalStateException("Redis 补偿未完成，订单消息保留待重试");
        }
        // 若此步失败，重投时 Lua 会识别 compensated 标记，不会重复归还库存。
        persistenceService.completeCompensation(task.getOrderId());
    }
}
