package com.hmdp.config;

import org.junit.jupiter.api.Test;
import org.redisson.client.codec.StringCodec;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class RedissonConfigTest {
    @Test
    void reusesResolvedDatabaseCredentialsTlsAndTimeoutWithoutConnecting() {
        var redis = new RedisStandaloneConfiguration("::1", 6380);
        redis.setDatabase(3);
        redis.setUsername("test-user");
        redis.setPassword("test-only-password");
        var client = LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2))
                .clientName("cache-test").useSsl().and().build();
        var factory = new LettuceConnectionFactory(redis, client);
        var config = RedissonConfig.lockConfig(factory);
        var server = config.useSingleServer();
        assertEquals("rediss://[::1]:6380", server.getAddress());
        assertEquals(3, server.getDatabase());
        assertEquals("test-user", server.getUsername());
        assertEquals("test-only-password", server.getPassword());
        assertEquals(2000, server.getTimeout());
        assertEquals("cache-test", server.getClientName());
        assertEquals(30_000, config.getLockWatchdogTimeout());
        assertTrue(config.isLazyInitialization());
        assertSame(StringCodec.INSTANCE, config.getCodec());
    }

    @Test
    void clusterCannotAccidentallyUseDifferentStandaloneLockServer() {
        var factory = new LettuceConnectionFactory(new RedisClusterConfiguration());
        assertThrows(IllegalStateException.class, () -> RedissonConfig.lockConfig(factory));
    }
}
