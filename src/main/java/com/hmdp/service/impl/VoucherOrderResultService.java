package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.dto.VoucherOrderResultDTO;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.entity.VoucherOrderTask;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.mapper.VoucherOrderTaskMapper;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

import static com.hmdp.entity.VoucherOrderTask.*;
import static com.hmdp.utils.RedisConstants.SECKILL_RESULT_KEY;

@Service
public class VoucherOrderResultService {
    @Resource
    private VoucherOrderMapper orderMapper;
    @Resource
    private VoucherOrderTaskMapper taskMapper;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public Result queryResult(Long orderId) {
        if (orderId == null || orderId <= 0) return Result.fail("订单ID必须为正数");
        UserDTO user = UserHolder.getUser();
        if (user == null) return Result.fail("请先登录");
        VoucherOrderTask task = taskMapper.selectById(orderId);
        VoucherOrder order = orderMapper.selectById(orderId);
        if (order != null) {
            if (!user.getId().equals(order.getUserId())) return notFound();
            return response(orderId, order.getVoucherId(), SUCCESS, order.getStatus(), "下单成功", false);
        }
        if (task != null) {
            if (!user.getId().equals(task.getUserId())) return notFound();
            boolean failed = FAILED.equals(task.getStatus());
            String message = failed ? task.getFailureReason()
                    : COMPENSATING.equals(task.getStatus()) ? "下单失败，正在恢复库存，请稍后查询" : "订单处理中";
            return response(orderId, task.getVoucherId(), failed ? FAILED : PROCESSING, null, message, failed);
        }
        // 请求已入 Redis 队列而消费者尚未创建数据库记录时，仍可校验归属并返回处理中。
        Map<Object, Object> reservation = stringRedisTemplate.opsForHash().entries(SECKILL_RESULT_KEY + orderId);
        if (!user.getId().toString().equals(reservation.get("userId"))
                || !orderId.toString().equals(reservation.get("orderId"))
                || reservation.get("voucherId") == null) return notFound();
        return Result.ok(new VoucherOrderResultDTO(orderId.toString(), reservation.get("voucherId").toString(),
                PROCESSING, null, "订单处理中", false));
    }

    private Result response(Long id, Long voucherId, String status, Integer orderStatus,
                            String message, boolean compensated) {
        return Result.ok(new VoucherOrderResultDTO(id.toString(), voucherId.toString(), status,
                orderStatus, message, compensated));
    }

    private Result notFound() {
        // 无权限和不存在使用相同提示，避免泄露其他用户是否有这笔订单。
        return Result.fail("下单记录不存在");
    }
}
