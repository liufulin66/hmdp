package com.hmdp.config;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.baomidou.mybatisplus.core.MybatisParameterHandler;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.utils.GlobalIdGenerator;
import lombok.Data;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.builder.StaticSqlSource;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class SnowflakeIdConfigTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SnowflakeIdConfig.class);

    @Test
    void defaultConfigurationExposesSameSingletonThroughBothTypes() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(GlobalIdGenerator.class)
                    .hasSingleBean(IdentifierGenerator.class);
            GlobalIdGenerator generator = context.getBean(GlobalIdGenerator.class);
            assertThat(context.getBean(IdentifierGenerator.class)).isSameAs(generator);
            long id = generator.nextId();
            assertThat((id >>> 12) & 31L).isEqualTo(1L);
            assertThat((id >>> 17) & 31L).isEqualTo(1L);
        });
    }

    @Test
    void configurationBindsExplicitNodeNumbers() {
        runner.withPropertyValues("hmdp.id-generator.worker-id=9", "hmdp.id-generator.datacenter-id=6")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    long id = context.getBean(GlobalIdGenerator.class).nextId();
                    assertThat((id >>> 12) & 31L).isEqualTo(9L);
                    assertThat((id >>> 17) & 31L).isEqualTo(6L);
                });
    }

    @Test
    void applicationYamlResolvesNodeNumbersFromEnvironmentVariables() {
        runner.withInitializer(context -> {
            YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
            yaml.setResources(new ClassPathResource("application.yaml"));
            Properties properties = yaml.getObject();
            Map<String, Object> idSettings = new HashMap<>();
            // 仅加载 ID 配置，测试不启动数据源，也不输出应用中的连接信息。
            properties.forEach((key, value) -> {
                if (key.toString().startsWith("hmdp.id-generator.")) {
                    idSettings.put(key.toString(), value);
                }
            });
            context.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("id-settings-from-yaml", idSettings));
            context.getEnvironment().getPropertySources().addFirst(
                    new SystemEnvironmentPropertySource("id-test-environment", Map.of(
                            "HMDP_ID_WORKER_ID", "14", "HMDP_ID_DATACENTER_ID", "8")));
        }).run(context -> {
            assertThat(context).hasNotFailed();
            long id = context.getBean(GlobalIdGenerator.class).nextId();
            assertThat((id >>> 12) & 31L).isEqualTo(14L);
            assertThat((id >>> 17) & 31L).isEqualTo(8L);
        });
    }

    @Test
    void invalidWorkerNumberPreventsStartup() {
        runner.withPropertyValues("hmdp.id-generator.worker-id=32").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .hasStackTraceContaining("hmdp.id-generator.worker-id");
        });
    }

    @Test
    void invalidDatacenterNumberPreventsStartup() {
        runner.withPropertyValues("hmdp.id-generator.datacenter-id=-1").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .hasStackTraceContaining("hmdp.id-generator.datacenter-id");
        });
    }

    @Test
    void mybatisUsesSameGeneratorAndPreservesExistingAndInputIdsWithoutDatabaseAccess() {
        runner.withUserConfiguration(DataSourceConfig.class)
                .withConfiguration(AutoConfigurations.of(MybatisPlusAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    GlobalIdGenerator generator = context.getBean(GlobalIdGenerator.class);
                    Configuration configuration = context.getBean(SqlSessionFactory.class).getConfiguration();
                    GlobalConfig globalConfig = GlobalConfigUtils.getGlobalConfig(configuration);
                    assertThat(globalConfig.getIdentifierGenerator()).isSameAs(generator);

                    long explicit = generator.nextId();
                    AutoLongEntity automatic = new AutoLongEntity();
                    prepareInsert(configuration, automatic);
                    assertThat(automatic.getId()).isGreaterThan(explicit);

                    AutoLongEntity preset = new AutoLongEntity();
                    preset.setId(explicit);
                    prepareInsert(configuration, preset);
                    assertThat(preset.getId()).isEqualTo(explicit);

                    AutoStringEntity textId = new AutoStringEntity();
                    prepareInsert(configuration, textId);
                    assertThat(Long.parseLong(textId.getId())).isGreaterThan(automatic.getId());

                    VoucherOrder inputOrder = new VoucherOrder();
                    inputOrder.setId(generator.nextId());
                    Long assignedId = inputOrder.getId();
                    prepareInsert(configuration, inputOrder);
                    assertThat(inputOrder.getId()).isEqualTo(assignedId);

                    AutoIncrementEntity databaseAssigned = new AutoIncrementEntity();
                    prepareInsert(configuration, databaseAssigned);
                    assertThat(databaseAssigned.getId()).isNull();

                    verifyNoInteractions(context.getBean(DataSource.class));
                });
    }

    private void prepareInsert(Configuration configuration, Object entity) {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "id-generator-test");
        assistant.setCurrentNamespace("id-generator-test");
        TableInfoHelper.initTableInfo(assistant, entity.getClass());
        MappedStatement statement = new MappedStatement.Builder(configuration,
                "id-generator-test.insert." + entity.getClass().getSimpleName(),
                new StaticSqlSource(configuration, "INSERT INTO id_generator_test (id) VALUES (?)"),
                SqlCommandType.INSERT).build();
        // 只运行 MyBatis-Plus 插入前的主键填充，不执行任何 SQL。
        new MybatisParameterHandler(statement, entity, statement.getBoundSql(entity));
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class DataSourceConfig {
        @Bean
        DataSource dataSource() {
            return mock(DataSource.class);
        }
    }

    @Data
    @TableName("id_generator_test_long")
    static class AutoLongEntity {
        @TableId(type = IdType.ASSIGN_ID)
        private Long id;
    }

    @Data
    @TableName("id_generator_test_string")
    static class AutoStringEntity {
        @TableId(type = IdType.ASSIGN_ID)
        private String id;
    }

    @Data
    @TableName("id_generator_test_auto_increment")
    static class AutoIncrementEntity {
        @TableId(type = IdType.AUTO)
        private Long id;
    }
}
