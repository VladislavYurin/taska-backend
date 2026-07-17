package ru.taska.config;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.core.env.Environment;
import ru.taska.aspect.MetricsAspect;

@Configuration
@ConditionalOnClass(MetricsAspect.class) // Создать конфигурацию, если MetricsAspect есть в classpath
@EnableAspectJAutoProxy(proxyTargetClass = true,exposeProxy = true) // Включает поддержку AOP (без этого point-cut @Around не работает)
@Slf4j
public class MetricsAutoConfiguration {

    @Bean
    @ConditionalOnBean(MeterRegistry.class) // Создаст бин, если есть бин MeterRegistry
    @ConditionalOnMissingBean // Создаст бин, если его еще нет
    public MetricsAspect metricsAspect(MeterRegistry meterRegistry, Environment environment){
        log.info("MetricsAspect bean created! Metrics tracking is enabled.");
        return new MetricsAspect(meterRegistry,environment);
    }
}
