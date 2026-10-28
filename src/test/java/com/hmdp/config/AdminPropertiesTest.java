package com.hmdp.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AdminPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfig.class);

    @Test
    void missingWhitelistDefaultsToEmpty() {
        runner.run(context -> assertThat(context.getBean(AdminProperties.class).getUserIds()).isEmpty());
    }

    @Test
    void applicationYamlEmptyEnvironmentBindsToEmptyWhitelist() {
        withEnvironment("").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AdminProperties.class).getUserIds()).isEmpty();
        });
    }

    @Test
    void environmentListBindsToUserIds() {
        withEnvironment("1,2").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AdminProperties.class).getUserIds()).containsExactlyInAnyOrder(1L, 2L);
        });
    }

    private ApplicationContextRunner withEnvironment(String ids) {
        return runner.withInitializer(context -> {
            YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
            yaml.setResources(new ClassPathResource("application.yaml"));
            Map<String, Object> properties = new HashMap<>();
            yaml.getObject().forEach((key, value) -> {
                if (key.toString().startsWith("hmdp.admin.")) properties.put(key.toString(), value);
            });
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("admin-yaml", properties));
            context.getEnvironment().getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                    "admin-environment", Map.of("HMDP_ADMIN_USER_IDS", ids)));
        });
    }

    @Configuration
    @EnableConfigurationProperties(AdminProperties.class)
    static class PropertiesConfig { }
}
