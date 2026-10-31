package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {

    @Resource
    private IFollowService followService;

    @PutMapping("/{followUserId}/{isFollow}")
    public Result follow(@PathVariable("followUserId") Long followUserId,
                         @PathVariable("isFollow") String isFollow) {
        // 只接受接口约定的 true/false，避免把拼写错误当成取关。
        if (!"true".equals(isFollow) && !"false".equals(isFollow)) {
            return Result.fail("关注状态只能为true或false");
        }
        return followService.follow(followUserId, Boolean.valueOf(isFollow));
    }

    @GetMapping("/or/not/{followUserId}")
    public Result isFollow(@PathVariable("followUserId") Long followUserId) {
        return followService.isFollow(followUserId);
    }

    @GetMapping("/common/{followUserId}")
    public Result common(@PathVariable("followUserId") Long followUserId,
                         @RequestParam(value = "current", defaultValue = "1") Integer current) {
        return followService.commonFollow(followUserId, current);
    }
}
