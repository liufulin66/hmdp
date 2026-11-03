package com.hmdp.dto;
import com.hmdp.entity.Shop;
import java.util.List;
public record ShopPage(List<Shop> list, boolean hasMore) {}
