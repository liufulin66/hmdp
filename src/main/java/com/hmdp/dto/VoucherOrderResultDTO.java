package com.hmdp.dto;

/** 编号使用字符串，避免 JavaScript 大整数精度丢失。 */
public record VoucherOrderResultDTO(String orderId, String voucherId, String status,
                                    Integer orderStatus, String message, boolean compensated) {
}
