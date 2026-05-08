package ru.taska.config;

import converter.JsonNodeToJsonConverter;
import converter.JsonToJsonNodeConverter;
import tools.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.r2dbc.convert.R2dbcCustomConversions;
import org.springframework.data.r2dbc.dialect.PostgresDialect;

import java.util.List;

@Configuration
public class R2dbcConfig {

    @Bean
    public R2dbcCustomConversions r2dbcCustomConversions(ObjectMapper objectMapper) {
        return R2dbcCustomConversions.of(
                PostgresDialect.INSTANCE,
                List.of(
                        new JsonToJsonNodeConverter(objectMapper),
                        new JsonNodeToJsonConverter()
                )
        );
    }
}
