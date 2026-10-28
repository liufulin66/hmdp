package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.entity.VoucherOrderTask;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface VoucherOrderTaskMapper extends BaseMapper<VoucherOrderTask> {
    @Insert("""
            INSERT INTO tb_voucher_order_task (order_id,user_id,voucher_id,status)
            VALUES (#{orderId},#{userId},#{voucherId},'PROCESSING')
            ON DUPLICATE KEY UPDATE order_id=order_id
            """)
    int ensureTask(@Param("orderId") long orderId, @Param("userId") long userId,
                   @Param("voucherId") long voucherId);

    @Select("SELECT * FROM tb_voucher_order_task WHERE order_id=#{orderId} FOR UPDATE")
    VoucherOrderTask lockTask(@Param("orderId") long orderId);
}
