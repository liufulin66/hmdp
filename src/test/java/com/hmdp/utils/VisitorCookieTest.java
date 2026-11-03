package com.hmdp.utils;

import com.hmdp.config.StatisticsProperties;
import com.hmdp.dto.UserDTO;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class VisitorCookieTest {
    private final StatisticsProperties properties = new StatisticsProperties();
    private final VisitorCookie visitors = new VisitorCookie(properties);
    @AfterEach void clear() { UserHolder.removeUser(); }

    @Test void missingCookieGeneratesPersistentHttpOnlyBrowserIdentity() {
        var response = new MockHttpServletResponse(); String id = visitors.identify(new MockHttpServletRequest(), response);
        assertTrue(VisitorCookie.valid(id));
        String cookie = response.getHeader("Set-Cookie");
        assertTrue(cookie.contains("Path=/")); assertTrue(cookie.contains("HttpOnly")); assertTrue(cookie.contains("SameSite=Lax"));
        assertTrue(cookie.contains("Max-Age=31536000")); assertFalse(cookie.contains("Secure"));
    }
    @Test void validCookieIsStableThroughLoginAndLogoutAndSecureIsConfigurable() {
        String id = UUID.randomUUID().toString(); var request = new MockHttpServletRequest();
        request.setCookies(new Cookie(VisitorCookie.NAME, id));
        assertEquals(id, visitors.identify(request, new MockHttpServletResponse()));
        UserDTO user = new UserDTO(); user.setId(99L); UserHolder.saveUser(user);
        assertEquals(id, visitors.identify(request, new MockHttpServletResponse())); UserHolder.removeUser();
        properties.setCookieSecure(true); var response = new MockHttpServletResponse();
        assertEquals(id, visitors.identify(request, response)); assertTrue(response.getHeader("Set-Cookie").contains("Secure"));
    }
    @Test void malformedAndOversizedValuesAreReplacedRatherThanUsed() {
        for (String value : new String[]{"", "abc", "a".repeat(2000), "00000000-0000-0000-0000-000000000000", "12345678-1234-4234-7234-123456789012"}) {
            var request = new MockHttpServletRequest(); request.setCookies(new Cookie(VisitorCookie.NAME, value));
            String replacement = visitors.identify(request, new MockHttpServletResponse());
            assertTrue(VisitorCookie.valid(replacement)); assertNotEquals(value, replacement);
        }
    }
}
