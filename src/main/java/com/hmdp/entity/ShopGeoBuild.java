package com.hmdp.entity;
import lombok.Data;
@Data
public class ShopGeoBuild {
    private Integer id;
    private String status;
    private String claimToken;
    private Long leaseUntil;
    private Long requestedAt;
    private String lastSuccessfulGeneration;
    private String lastError;
}
