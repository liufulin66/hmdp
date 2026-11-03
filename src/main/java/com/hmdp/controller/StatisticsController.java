package com.hmdp.controller;

import com.hmdp.annotation.AdminOnly;
import com.hmdp.annotation.AnonymousPost;
import com.hmdp.dto.Result;
import com.hmdp.dto.VisitRequest;
import com.hmdp.service.impl.StatisticsService;
import com.hmdp.utils.VisitorCookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/statistics")
public class StatisticsController {
    private final StatisticsService statistics;
    private final VisitorCookie visitors;
    public StatisticsController(StatisticsService statistics, VisitorCookie visitors) { this.statistics = statistics; this.visitors = visitors; }

    @AnonymousPost
    @PostMapping(value = "/visits", consumes = "application/json")
    public Result visit(@RequestBody VisitRequest event, HttpServletRequest request, HttpServletResponse response) {
        if (event == null || event.pageType() == null) return Result.fail("浏览事件类型不能为空");
        return statistics.record(visitors.identify(request, response));
    }

    @AdminOnly
    @GetMapping("/uv/daily")
    public Result daily(@RequestParam(value = "date", required = false) String date) { return statistics.daily(date); }

    @AdminOnly
    @GetMapping("/uv/recent")
    public Result recent() { return statistics.recent(); }
}
