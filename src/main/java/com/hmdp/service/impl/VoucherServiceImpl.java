package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static com.hmdp.utils.RedisConstants.SECKILL_META_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

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
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryVoucherOfShop(Long shopId) {
        // 查询优惠券信息
        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        // 返回结果
        return Result.ok(vouchers);
    }

    @Override
    public Result addVoucher(Voucher voucher) {
        String error = validateVoucher(voucher);
        if (error != null) {
            return Result.fail(error);
        }
        // 类型和状态由服务端决定，不信任客户端传值
        voucher.setType(0);
        voucher.setStatus(1);
        if (!save(voucher)) {
            return Result.fail("新增优惠券失败");
        }
        return Result.ok(voucher.getId());
    }

    @Override
    @Transactional
    public Result addSeckillVoucher(Voucher voucher) {
        String error = validateVoucher(voucher);
        if (error == null) {
            error = validateSeckill(voucher);
        }
        if (error != null) {
            return Result.fail(error);
        }
        // 类型和状态由服务端决定，不信任客户端传值
        voucher.setType(1);
        voucher.setStatus(1);
        if (!save(voucher)) {
            return Result.fail("新增优惠券失败");
        }
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());
        if (!seckillVoucherService.save(seckillVoucher)) {
            throw new IllegalStateException("新增秒杀信息失败");
        }
        // 事务提交后再写 Redis，避免回滚时留下不存在的库存
        preheatAfterCommit(voucher.getId(), voucher.getStock(),
                voucher.getBeginTime(), voucher.getEndTime());
        return Result.ok(voucher.getId());
    }

    private String validateVoucher(Voucher voucher) {
        if (voucher == null) {
            return "优惠券信息不能为空";
        }
        if (voucher.getShopId() == null || voucher.getShopId() <= 0) {
            return "商铺ID必须为正数";
        }
        if (StrUtil.isBlank(voucher.getTitle())) {
            return "优惠券标题不能为空";
        }
        if (voucher.getTitle().length() > 255) {
            return "优惠券标题不能超过255个字符";
        }
        if (voucher.getSubTitle() != null && voucher.getSubTitle().length() > 255) {
            return "优惠券副标题不能超过255个字符";
        }
        if (voucher.getRules() != null && voucher.getRules().length() > 1024) {
            return "使用规则不能超过1024个字符";
        }
        if (voucher.getPayValue() == null || voucher.getPayValue() < 0) {
            return "支付金额不能为负数";
        }
        if (voucher.getActualValue() == null || voucher.getActualValue() <= 0) {
            return "抵扣金额必须大于0";
        }
        if (voucher.getPayValue() > voucher.getActualValue()) {
            return "支付金额不能大于抵扣金额";
        }
        return null;
    }

    private String validateSeckill(Voucher voucher) {
        if (voucher.getStock() == null || voucher.getStock() <= 0) {
            return "秒杀库存必须大于0";
        }
        if (voucher.getBeginTime() == null || voucher.getEndTime() == null) {
            return "秒杀开始和结束时间不能为空";
        }
        if (!voucher.getBeginTime().isBefore(voucher.getEndTime())) {
            return "秒杀开始时间必须早于结束时间";
        }
        return null;
    }

    private void preheatAfterCommit(Long voucherId, Integer stock,
                                    LocalDateTime beginTime, LocalDateTime endTime) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    preheat(voucherId, stock, beginTime, endTime);
                }
            });
        } else {
            preheat(voucherId, stock, beginTime, endTime);
        }
    }

    private void preheat(Long voucherId, Integer stock,
                         LocalDateTime beginTime, LocalDateTime endTime) {
        try {
            // 活动期间库存 key 不能过期，否则已扣减的库存可能被重新初始化
            stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY + voucherId, String.valueOf(stock));
            stringRedisTemplate.opsForHash().putAll(SECKILL_META_KEY + voucherId, Map.of(
                    "beginTime", String.valueOf(toEpochMillis(beginTime)),
                    "endTime", String.valueOf(toEpochMillis(endTime))));
        } catch (DataAccessException e) {
            // 数据库事务已提交，不能因为缓存失败让调用方误以为新增失败
            log.error("秒杀活动预热失败，voucherId={}，stock={}", voucherId, stock, e);
        }
    }

    private long toEpochMillis(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
