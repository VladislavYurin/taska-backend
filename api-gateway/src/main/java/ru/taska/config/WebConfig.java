package ru.taska.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import ru.taska.domain.dto.IssuePriorityDto;
import ru.taska.domain.dto.IssueTypeDto;
import ru.taska.domain.dto.OutboxServiceTypeDto;
import ru.taska.domain.dto.SortOrderDto;

import java.util.function.Function;

/**
 * Конфигурация Spring WebFlux для регистрации конвертеров строковых
 * query- и path-параметров в OpenAPI DTO-перечисления.
 * <p>
 * Конвертеры поддерживают значения в любом регистре и игнорируют пробелы
 * по краям значения. Для неизвестных значений возвращается {@code null},
 * что позволяет обработать их на уровне контроллера без ошибки HTTP 400.
 */
@Configuration
public class WebConfig implements WebFluxConfigurer {

    /**
     * Регистрирует конвертеры строковых query- и path-параметров
     * в OpenAPI DTO-перечисления.
     *
     * @param registry реестр конвертеров Spring
     */
    @Override
    public void addFormatters(FormatterRegistry registry) {
        addStrictConverter(registry, SortOrderDto.class, SortOrderDto::fromValue);
        addStrictConverter(registry, OutboxServiceTypeDto.class, OutboxServiceTypeDto::fromValue);

        addLenientConverter(registry, IssuePriorityDto.class, IssuePriorityDto::fromValue);
        addLenientConverter(registry, IssueTypeDto.class, IssueTypeDto::fromValue);
    }

    private static <T> void addStrictConverter(
            FormatterRegistry registry,
            Class<T> targetType,
            Function<String, T> converter
    ) {
        registry.addConverter(String.class, targetType, source -> converter.apply(source.trim()));
    }

    private static <T> void addLenientConverter(
            FormatterRegistry registry,
            Class<T> targetType,
            Function<String, T> converter
    ) {
        registry.addConverter(String.class, targetType,
                source -> convertEnum(source, converter));
    }

    private static <T> T convertEnum(String source, Function<String, T> converter) {
        if (source.isBlank()) {
            return null;
        }

        try {
            return converter.apply(source.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
