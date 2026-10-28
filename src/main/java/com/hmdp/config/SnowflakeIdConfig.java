package com.hmdp.config;

import com.hmdp.utils.GlobalIdGenerator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SnowflakeIdProperties.class)
public class SnowflakeIdConfig {
    /**
     * MyBatis-Plus 自动配置会识别这个 IdentifierGenerator Bean，
     * 使 ORM 自动分配主键与业务提前发号共用同一个生成器。
     */
    @Bean
    public GlobalIdGenerator globalIdGenerator(SnowflakeIdProperties properties) {
        return new GlobalIdGenerator(properties.getWorkerId(), properties.getDatacenterId());
    }
}
