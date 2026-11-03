package com.hmdp.interceptor;

import com.hmdp.config.AdminProperties;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import com.hmdp.controller.ShopGeoController;
import com.hmdp.annotation.AdminOnly;
import com.hmdp.annotation.AnonymousPost;
import org.springframework.web.method.HandlerMethod;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/** 商户和优惠券的查询公开，其余操作只允许白名单管理员。 */
public class AdminInterceptor implements HandlerInterceptor {
    private final AdminProperties properties;

    public AdminInterceptor(AdminProperties properties) {
        this.properties = properties;
    }

    public static boolean isPublicRead(HttpServletRequest request) {
        return "GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod())
                || "OPTIONS".equals(request.getMethod());
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // 按已匹配的控制器识别管理读取，避免 matrix 参数等 URL 变体绕过路径字符串判断。
        boolean geoManagement=handler instanceof HandlerMethod method
                && ShopGeoController.class.isAssignableFrom(method.getBeanType());
        boolean adminMethod = handler instanceof HandlerMethod method && method.hasMethodAnnotation(AdminOnly.class);
        if (!adminMethod && "POST".equals(request.getMethod()) && handler instanceof HandlerMethod method
                && method.hasMethodAnnotation(AnonymousPost.class)) return true;
        if (!geoManagement && !adminMethod && isPublicRead(request)) return true;
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        if (user.getId() == null || !properties.getUserIds().contains(user.getId())) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return false;
        }
        return true;
    }
}
