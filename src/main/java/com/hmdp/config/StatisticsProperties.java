package com.hmdp.config;

import com.hmdp.utils.StatisticsPolicy;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalDate;

@Data
@ConfigurationProperties(prefix = "hmdp.statistics")
public class StatisticsProperties {
    private String startDate = "2026-11-03";
    private boolean cookieSecure;

    public LocalDate collectionStart() { return StatisticsPolicy.date(startDate); }
    @PostConstruct void validate() { collectionStart(); }
}
