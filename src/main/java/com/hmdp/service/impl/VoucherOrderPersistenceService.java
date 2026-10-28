package com.hmdp.service.impl;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;

/**
 * 秒杀订单落库。独立 Bean 是为了让 @Transactional 走 Spring 代理：
 * 如果和消费者写在同一个类里用 this 调用，事务不会生效。
 */
@Service
public class VoucherOrderPersistenceService {

    @Resource
    private VoucherOrderMapper voucherOrderMapper;

    @Resource
    private SeckillVoucherMapper seckillVoucherMapper;

    @Transactional
    public void persistVoucherOrder(long orderId, long userId, long voucherId) {
        VoucherOrder order = new VoucherOrder();
        order.setId(orderId);
        order.setUserId(userId);
        order.setVoucherId(voucherId);
        order.setPayType(1);
        order.setStatus(1);
        // 先插入订单：唯一索引在这里拦截重复消费，异常会让整个事务回滚
        if (voucherOrderMapper.insert(order) == 0) {
            throw new IllegalStateException("创建秒杀订单失败，orderId=" + orderId);
        }
        // 再扣数据库库存：条件更新是防超卖的最后一道防线
        if (seckillVoucherMapper.deductStock(voucherId) == 0) {
            throw new IllegalStateException("数据库库存不足，voucherId=" + voucherId);
        }
    }
}
