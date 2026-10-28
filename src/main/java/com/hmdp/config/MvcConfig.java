package com.hmdp.config;

import com.hmdp.interceptor.LoginInterceptor;
import com.hmdp.interceptor.AdminInterceptor;
import com.hmdp.interceptor.RefreshTokenInterceptor;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableConfigurationProperties(AdminProperties.class)
public class MvcConfig implements WebMvcConfigurer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private AdminProperties adminProperties;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // order(0)：先执行，识别用户 + 续期，永远放行
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate))
                .addPathPatterns("/**")
                .order(0);

        // order(1)：后执行，只校验需要登录的接口
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/user/code",
                        "/user/login",
                        "/shop/**",
                        "/voucher/**",
                        "/blog/hot",
                        "/shop-type/**",
                        "/error"
                )
                .order(1);

        // /shop/** 和 /voucher/** 的公开查询与管理写入按 HTTP 方法区分。
        registry.addInterceptor(new AdminInterceptor(adminProperties))
                .addPathPatterns("/shop", "/shop/**", "/voucher", "/voucher/**")
                .order(2);
    }
}
