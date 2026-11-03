package com.hmdp.utils;

import com.hmdp.config.StatisticsProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class VisitorCookie {
    public static final String NAME = "hmdp_visitor_id";
    private static final Pattern UUID_V4 = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}", Pattern.CASE_INSENSITIVE);
    private final StatisticsProperties properties;
    public VisitorCookie(StatisticsProperties properties) { this.properties = properties; }

    public String identify(HttpServletRequest request, HttpServletResponse response) {
        String visitor = null;
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (NAME.equals(cookie.getName()) && valid(cookie.getValue())) {
                    visitor = cookie.getValue().toLowerCase(Locale.ROOT); break;
                }
            }
        }
        if (visitor == null) visitor = UUID.randomUUID().toString();
        // 更新同一标识的 Cookie 寿命；不会更新 HLL 的固定到期点。
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(NAME, visitor)
                .path("/").httpOnly(true).sameSite("Lax").secure(properties.isCookieSecure())
                .maxAge(Duration.ofDays(365)).build().toString());
        return visitor;
    }

    public static boolean valid(String value) { return value != null && value.length() == 36 && UUID_V4.matcher(value).matches(); }
}
