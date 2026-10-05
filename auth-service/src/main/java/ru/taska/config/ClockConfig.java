package ru.taska.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Конфигурация времени.
 *
 * <p>Отдельный бин {@link Clock}, чтобы можно было подменять время в тестах
 * (детерминированная проверка истечения лок-окна без {@code Thread.sleep}).</p>
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
