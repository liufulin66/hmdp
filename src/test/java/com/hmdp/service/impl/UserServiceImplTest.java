package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

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

    @Test
    void profileContainsOnlyThePublicDtoAndHandlesMissingUsers() {
        UserMapper mapper = mock(UserMapper.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        when(mapper.selectById(2L)).thenReturn(new User().setId(2L).setNickName("two")
                .setIcon("two.png").setPhone("private phone").setPassword("private password"));
        Result result = service.queryUserById(2L);
        assertTrue(result.getSuccess());
        UserDTO dto = (UserDTO) result.getData();
        assertEquals(2L, dto.getId());
        assertEquals("two", dto.getNickName());
        assertEquals("two.png", dto.getIcon());
        assertFalse(service.queryUserById(99L).getSuccess());
        assertFalse(service.queryUserById(null).getSuccess());
        assertFalse(service.queryUserById(0L).getSuccess());
        verifyNoMoreInteractions(redis);
    }
}
