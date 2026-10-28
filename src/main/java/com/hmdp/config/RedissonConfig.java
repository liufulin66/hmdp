package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/** 缓存重建锁使用 Redisson，复用 Lettuce 已解析的单机连接配置（含 URL 和认证）。 */
@Configuration(proxyBeanMethods = false)
public class RedissonConfig {
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(LettuceConnectionFactory connectionFactory) {
        return Redisson.create(lockConfig(connectionFactory));
    }

    public static Config lockConfig(LettuceConnectionFactory connectionFactory) {
        if (connectionFactory.isClusterAware() || connectionFactory.isRedisSentinelAware()) {
            throw new IllegalStateException("缓存锁当前仅支持单机 Redis，集群或哨兵需同步配置 Redisson");
        }
        RedisStandaloneConfiguration redis = connectionFactory.getStandaloneConfiguration();
        if (redis == null) {
            throw new IllegalStateException("缓存锁需要 Redis 单机连接配置");
        }
        var client = connectionFactory.getClientConfiguration();
        Config config = new Config();
        config.setCodec(StringCodec.INSTANCE);
        config.setLazyInitialization(true);
        // tryLock() 不指定租期时，看门狗在持锁期间续期；进程退出后锁自然过期。
        config.setLockWatchdogTimeout(30_000);
        String host = redis.getHostName();
        if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";
        SingleServerConfig server = config.useSingleServer()
                .setAddress((client.isUseSsl() ? "rediss://" : "redis://") + host + ":" + redis.getPort())
                .setDatabase(redis.getDatabase())
                .setUsername(redis.getUsername())
                .setTimeout(Math.toIntExact(client.getCommandTimeout().toMillis()))
                .setConnectionMinimumIdleSize(1)
                .setConnectionPoolSize(10)
                .setSubscriptionConnectionMinimumIdleSize(1)
                .setSubscriptionConnectionPoolSize(2);
        if (redis.getPassword().isPresent()) {
            server.setPassword(new String(redis.getPassword().get()));
        }
        client.getClientOptions().ifPresent(options -> server.setConnectTimeout(
                Math.toIntExact(options.getSocketOptions().getConnectTimeout().toMillis())));
        client.getClientName().ifPresent(server::setClientName);
        return config;
    }
}
