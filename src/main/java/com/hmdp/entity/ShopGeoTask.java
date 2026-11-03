package com.hmdp.entity;
import lombok.Data;
@Data
public class ShopGeoTask {
    private Long id;
    private Long shopId;
    private Long version;
    private Long typeId;
    private Double x;
    private Double y;
    private String status;
    private Integer attempts;
    private Long nextAttemptAt;
    private Long leaseUntil;
    private String claimToken;
    private String lastError;
}
