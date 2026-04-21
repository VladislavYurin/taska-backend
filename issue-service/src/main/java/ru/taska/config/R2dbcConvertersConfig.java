package ru.taska.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.r2dbc.convert.R2dbcCustomConversions;
import org.springframework.data.r2dbc.dialect.PostgresDialect;
import ru.taska.converter.JsonBToJsonNodeConverter;
import ru.taska.converter.JsonNodeToJsonBConverter;

import java.util.List;

@Configuration
@RequiredArgsConstructor
public class R2dbcConvertersConfig {

    private final JsonNodeToJsonBConverter jsonNodeToJsonBConverter;
    private final JsonBToJsonNodeConverter jsonBToJsonNodeConverter;

    @Bean
    public R2dbcCustomConversions r2dbcCustomConversions() {
        return R2dbcCustomConversions.of(
                PostgresDialect.INSTANCE,
                List.of(
                        jsonNodeToJsonBConverter,
                        jsonBToJsonNodeConverter
                )
        );
    }
}
