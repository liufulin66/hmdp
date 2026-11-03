package com.hmdp.interceptor;

import com.hmdp.utils.UserHolder;
import com.hmdp.annotation.AnonymousRead;
import com.hmdp.annotation.AnonymousPost;
import org.springframework.web.method.HandlerMethod;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod()))
                && handler instanceof HandlerMethod method && method.hasMethodAnnotation(AnonymousRead.class)) return true;
        if ("POST".equals(request.getMethod()) && handler instanceof HandlerMethod method
                && method.hasMethodAnnotation(AnonymousPost.class)) return true;
        // RefreshTokenInterceptor 已经把有效用户放进 ThreadLocal
        // 这里只判断有没有用户，不再查询 Redis
        if (UserHolder.getUser() == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED); // 401
            return false;
        }
        return true;
    }
}
