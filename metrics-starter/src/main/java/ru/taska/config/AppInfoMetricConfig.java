package ru.taska.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Конфигурация info-метрики app_info с именем приложения, версией приложения и хэшем коммита.
 */
@Configuration
@ConditionalOnClass(MeterRegistry.class)
public class AppInfoMetricConfig {

    private static final String DEFAULT_VALUE = "unknown";

    @Bean
    @ConditionalOnMissingBean(name = "appInfoMeterBinder")
    public MeterBinder appInfoMeterBinder(
            @Value("${spring.application.name:" + DEFAULT_VALUE + "}") String application,
            @Value("${GIT_COMMIT:" + DEFAULT_VALUE + "}") String commit,
            @Autowired(required = false) BuildProperties buildProperties) {

        String version = buildProperties != null && buildProperties.getVersion() != null
                ? buildProperties.getVersion()
                : DEFAULT_VALUE;

        return registry -> Gauge.builder("app_info", () -> 1.0)
                .tag("application", application)
                .tag("version", version)
                .tag("commit", commit)
                .description("Application build information")
                .register(registry);
    }
}
