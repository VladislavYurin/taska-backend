package ru.taska.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import ru.taska.domain.dto.OutboxServiceTypeDto;
import ru.taska.domain.dto.SortOrderDto;

@Configuration
public class WebConfig implements WebFluxConfigurer {

    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(String.class, SortOrderDto.class, source -> {
            if (source.isBlank()) {
                return null;
            }
            return SortOrderDto.fromValue(source.trim());
        });

        registry.addConverter(String.class, OutboxServiceTypeDto.class, source -> {
            if (source.isBlank()) {
                return null;
            }
            return OutboxServiceTypeDto.fromValue(source.trim());
        });
    }
}