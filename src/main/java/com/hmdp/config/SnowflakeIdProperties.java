package com.hmdp.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 节点编号默认仅适用于本机单实例，多实例须分别配置唯一的编号组合。 */
@Getter
@Setter
@ConfigurationProperties(prefix = "hmdp.id-generator")
public class SnowflakeIdProperties {
    private long workerId = 1L;
    private long datacenterId = 1L;
}
