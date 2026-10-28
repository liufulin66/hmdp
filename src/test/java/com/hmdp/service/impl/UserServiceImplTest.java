package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class UserServiceImplTest {
    private StringRedisTemplate redis;
    private UserServiceImpl service;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        service = new UserServiceImpl();
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
    }

    @Test
    void logoutDeletesOnlyTheCurrentToken() {
        Result result = service.logout("token-123");

        assertTrue(result.getSuccess());
        verify(redis).delete(LOGIN_USER_KEY + "token-123");
        verifyNoMoreInteractions(redis);
    }
}
