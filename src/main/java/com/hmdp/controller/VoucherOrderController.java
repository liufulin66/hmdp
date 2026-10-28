package com.hmdp.controller;


import com.hmdp.dto.Result;

import jakarta.annotation.Resource;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.impl.VoucherOrderResultService;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private VoucherOrderResultService resultService;

    @GetMapping("/{id}/result")
    public Result queryResult(@PathVariable("id") Long orderId) {
        return resultService.queryResult(orderId);
    }

    @PostMapping("seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
        return voucherOrderService.createVoucherOrder(voucherId);
    }
}
