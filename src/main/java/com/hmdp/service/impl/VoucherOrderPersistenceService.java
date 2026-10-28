package com.hmdp.service.impl;

import com.hmdp.entity.VoucherOrder;
import com.hmdp.entity.VoucherOrderTask;
import com.hmdp.mapper.SeckillVoucherMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.mapper.VoucherOrderTaskMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;

import static com.hmdp.entity.VoucherOrderTask.*;

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

    @Resource
    private VoucherOrderTaskMapper taskMapper;

    @Transactional
    public VoucherOrderTask persistVoucherOrder(long orderId, long userId, long voucherId) {
        VoucherOrderTask task = lockTask(orderId, userId, voucherId);
        // 失败状态也是终态：旧消息不能在库存已归还之后重新创建订单。
        if (!PROCESSING.equals(task.getStatus())) return task;
        VoucherOrder existing = voucherOrderMapper.selectById(orderId);
        if (existing != null) {
            verifyOrder(existing, userId, voucherId);
            return succeed(task);
        }
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
        // 与订单和库存同事务提交，避免落库成功却被另一个消费者判为失败。
        return succeed(task);
    }

    /** 在落库事务回滚后另开事务记录失败；数据库不可用时抛出异常，消息继续留在 pending。 */
    @Transactional
    public VoucherOrderTask recordFailure(long orderId, long userId, long voucherId) {
        VoucherOrderTask task = lockTask(orderId, userId, voucherId);
        if (!PROCESSING.equals(task.getStatus())) return task;
        VoucherOrder existing = voucherOrderMapper.selectById(orderId);
        if (existing != null) {
            verifyOrder(existing, userId, voucherId);
            return succeed(task);
        }
        VoucherOrder purchase = voucherOrderMapper.selectPurchase(userId, voucherId);
        if (purchase != null) {
            // 同用户已经有另一笔成功订单：归还本次多预扣的库存，但保留已购资格标记。
            task.setRestoreQualification(false);
            task.setStatus(COMPENSATING);
            task.setFailureReason("不能重复下单");
        } else {
            task.setRetryCount(task.getRetryCount() + 1);
            if (task.getRetryCount() >= 3) {
                task.setStatus(COMPENSATING);
                task.setFailureReason("下单失败，库存将自动恢复");
            }
        }
        saveTask(task);
        return task;
    }

    @Transactional
    public void completeCompensation(long orderId) {
        VoucherOrderTask task = taskMapper.lockTask(orderId);
        if (task != null && COMPENSATING.equals(task.getStatus())) {
            task.setStatus(FAILED);
            task.setFailureReason(Boolean.TRUE.equals(task.getRestoreQualification())
                    ? "下单失败，库存和购买资格已恢复" : "不能重复下单，本次预扣库存已恢复");
            saveTask(task);
        }
    }

    private VoucherOrderTask lockTask(long orderId, long userId, long voucherId) {
        taskMapper.ensureTask(orderId, userId, voucherId);
        VoucherOrderTask task = taskMapper.lockTask(orderId);
        if (task == null || !Long.valueOf(userId).equals(task.getUserId())
                || !Long.valueOf(voucherId).equals(task.getVoucherId())) {
            throw new IllegalStateException("下单处理记录与消息身份不一致");
        }
        return task;
    }

    private void verifyOrder(VoucherOrder order, long userId, long voucherId) {
        if (!Long.valueOf(userId).equals(order.getUserId())
                || !Long.valueOf(voucherId).equals(order.getVoucherId())) {
            throw new IllegalStateException("订单编号与购买人不一致");
        }
    }

    private VoucherOrderTask succeed(VoucherOrderTask task) {
        task.setStatus(SUCCESS);
        saveTask(task);
        return task;
    }

    private void saveTask(VoucherOrderTask task) {
        if (taskMapper.updateById(task) != 1) {
            throw new IllegalStateException("更新下单处理记录失败");
        }
    }
}
