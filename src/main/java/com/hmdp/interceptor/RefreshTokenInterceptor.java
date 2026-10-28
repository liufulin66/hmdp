package com.hmdp.interceptor;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.util.Map;

public class RefreshTokenInterceptor implements HandlerInterceptor {

    private final StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // 获取请求头中的 token
        String token = request.getHeader("authorization");
        if (token == null || token.isBlank()) {
            // 没带 token 也放行，是否 401 交给 LoginInterceptor 判断
            return true;
        }

        // 基于 token 从 Redis 查询用户
        String key = RedisConstants.LOGIN_USER_KEY + token;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);
        if (userMap.isEmpty()) {
            // token 无效或已过期也放行，同样交给 LoginInterceptor 判断
            return true;
        }

        // 将查询到的 hash 数据转为 UserDTO
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);

        // 刷新 token 有效期（每个请求都续期，相当于滑动过期）
        stringRedisTemplate.expire(key, Duration.ofMinutes(RedisConstants.LOGIN_USER_TTL));

        // 保存用户到 ThreadLocal，供后续 Controller 使用
        UserHolder.saveUser(userDTO);

        // 只负责识别用户，从不拦截请求
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, @Nullable Exception ex) throws Exception {
        // 请求结束清理 ThreadLocal，防止线程池复用导致用户信息串号
        UserHolder.removeUser();
    }
}
