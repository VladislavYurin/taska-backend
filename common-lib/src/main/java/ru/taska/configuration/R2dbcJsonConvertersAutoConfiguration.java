package ru.taska.configuration;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.r2dbc.convert.R2dbcCustomConversions;
import org.springframework.data.r2dbc.dialect.PostgresDialect;
import ru.taska.converter.JsonBToJsonNodeConverter;
import ru.taska.converter.JsonNodeToJsonBConverter;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

@AutoConfiguration(beforeName = "org.springframework.boot.data.r2dbc.autoconfigure.DataR2dbcAutoConfiguration")
@ConditionalOnClass(R2dbcCustomConversions.class)
public class R2dbcJsonConvertersAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public JsonNodeToJsonBConverter jsonNodeToJsonBConverter(ObjectMapper objectMapper) {
        return new JsonNodeToJsonBConverter(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public JsonBToJsonNodeConverter jsonBToJsonNodeConverter(ObjectMapper objectMapper) {
        return new JsonBToJsonNodeConverter(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public R2dbcCustomConversions r2dbcCustomConversions(
            JsonNodeToJsonBConverter jsonNodeToJsonBConverter,
            JsonBToJsonNodeConverter jsonBToJsonNodeConverter
    ) {
        return R2dbcCustomConversions.of(
                PostgresDialect.INSTANCE,
                List.of(
                        jsonNodeToJsonBConverter,
                        jsonBToJsonNodeConverter
                )
        );
    }
}
