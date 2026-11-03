package com.hmdp.controller;
import com.hmdp.dto.Result;
import com.hmdp.service.impl.ShopGeoBuildService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/shop/geo")
public class ShopGeoController {
    @Resource private ShopGeoBuildService builds;
    @PostMapping("/rebuild") public Result rebuild() { return builds.request(); }
    @GetMapping("/status") public Result status() { return builds.status(); }
}
