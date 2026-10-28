package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.GlobalIdGenerator;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static com.hmdp.utils.RedisConstants.SECKILL_META_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_ORDER_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_STREAM_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder>
        implements IVoucherOrderService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private GlobalIdGenerator globalIdGenerator;

    @Resource
    private DefaultRedisScript<Long> seckillScript;

    @Override
    public Result createVoucherOrder(Long voucherId) {
        if (voucherId == null || voucherId <= 0) {
            return Result.fail("优惠券ID必须为正数");
        }
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        try {
            if (!ensureMeta(voucherId)) {
                return Result.fail("秒杀活动不存在");
            }
            // 订单号必须在写 Redis 之前生成，消息和数据库订单共用同一个编号
            long orderId = globalIdGenerator.nextId();
            List<String> keys = List.of(
                    SECKILL_STOCK_KEY + voucherId,
                    SECKILL_ORDER_KEY + voucherId,
                    SECKILL_META_KEY + voucherId,
                    SECKILL_STREAM_KEY);
            Long result = stringRedisTemplate.execute(seckillScript, keys,
                    voucherId.toString(),
                    user.getId().toString(),
                    Long.toString(orderId),
                    Long.toString(System.currentTimeMillis()));
            if (result == null) {
                return Result.fail("秒杀失败，请稍后重试");
            }
            return switch (result.intValue()) {
                case 0 -> Result.ok(Long.toString(orderId));
                case 1 -> Result.fail("库存不足");
                case 2 -> Result.fail("不能重复下单");
                case 3 -> Result.fail("秒杀活动未就绪");
                case 4 -> Result.fail("秒杀尚未开始");
                case 5 -> Result.fail("秒杀已结束");
                default -> Result.fail("秒杀失败，请稍后重试");
            };
        } catch (DataAccessException e) {
            log.error("秒杀服务依赖异常，voucherId={}，userId={}", voucherId, user.getId(), e);
            return Result.fail("秒杀服务繁忙，请稍后重试");
        }
    }

    /**
     * 新创建的秒杀券会在事务提交后预热元数据；这里兼容历史数据：
     * 元数据缺失时从数据库补齐，并顺手修复缺失的 Redis 库存。
     */
    private boolean ensureMeta(Long voucherId) {
        String metaKey = SECKILL_META_KEY + voucherId;
        Map<Object, Object> meta = stringRedisTemplate.opsForHash().entries(metaKey);
        if (meta.get("beginTime") != null && meta.get("endTime") != null) {
            return true;
        }
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        if (seckillVoucher == null || seckillVoucher.getBeginTime() == null
                || seckillVoucher.getEndTime() == null) {
            return false;
        }
        stringRedisTemplate.opsForHash().putAll(metaKey, Map.of(
                "beginTime", String.valueOf(toEpochMillis(seckillVoucher.getBeginTime())),
                "endTime", String.valueOf(toEpochMillis(seckillVoucher.getEndTime()))));
        String stockKey = SECKILL_STOCK_KEY + voucherId;
        if (!Boolean.TRUE.equals(stringRedisTemplate.hasKey(stockKey))
                && seckillVoucher.getStock() != null) {
            stringRedisTemplate.opsForValue().setIfAbsent(stockKey, String.valueOf(seckillVoucher.getStock()));
        }
        return true;
    }

    private long toEpochMillis(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
