package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 下单处理状态，与订单的支付/核销状态分开保存。 */
@Data
@TableName("tb_voucher_order_task")
public class VoucherOrderTask {
    public static final String PROCESSING = "PROCESSING";
    public static final String SUCCESS = "SUCCESS";
    public static final String COMPENSATING = "COMPENSATING";
    public static final String FAILED = "FAILED";

    @TableId(type = IdType.INPUT)
    private Long orderId;
    private Long userId;
    private Long voucherId;
    private String status;
    private Integer retryCount;
    private String failureReason;
    private Boolean restoreQualification;
}
