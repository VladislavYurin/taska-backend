package ru.taska.converter;

import io.r2dbc.postgresql.codec.Json;
import lombok.RequiredArgsConstructor;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ReadingConverter
@RequiredArgsConstructor
public class JsonBToJsonNodeConverter implements Converter<Json, JsonNode> {

    private final ObjectMapper objectMapper;

    @Override
    public JsonNode convert(Json source) {
        try {
            return objectMapper.readTree(source.asString());
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Failed to deserialize jsonb to JsonNode", e);
        }
    }
}
