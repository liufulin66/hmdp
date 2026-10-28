package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashSet;
import java.util.Set;

@Data
@ConfigurationProperties(prefix = "hmdp.admin")
public class AdminProperties {
    private Set<Long> userIds = new HashSet<>();
}
