package ru.taska.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Gauge-метрики health_liveness и health_readiness.
 * Значение 1.0 — CORRECT/ACCEPTING_TRAFFIC, 0.0 — BROKEN/REFUSING_TRAFFIC.
 */
@Configuration
@ConditionalOnClass(MeterRegistry.class)
public class HealthProbeMetricConfig {

    @Bean
    @ConditionalOnMissingBean(name = "healthProbeMeterBinder")
    public MeterBinder healthProbeMeterBinder(ApplicationAvailability availability) {
        return registry -> {
            Gauge.builder("health_liveness", availability,
                            a -> a.getLivenessState() == LivenessState.CORRECT ? 1.0 : 0.0)
                    .description("Liveness probe status: 1 = CORRECT, 0 = BROKEN")
                    .register(registry);

            Gauge.builder("health_readiness", availability,
                            a -> a.getReadinessState() == ReadinessState.ACCEPTING_TRAFFIC ? 1.0 : 0.0)
                    .description("Readiness probe status: 1 = ACCEPTING_TRAFFIC, 0 = REFUSING_TRAFFIC")
                    .register(registry);
        };
    }
}
