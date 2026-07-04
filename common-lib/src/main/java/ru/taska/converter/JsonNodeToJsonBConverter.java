package ru.taska.converter;

import io.r2dbc.postgresql.codec.Json;
import lombok.RequiredArgsConstructor;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.WritingConverter;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@WritingConverter
@RequiredArgsConstructor
public class JsonNodeToJsonBConverter implements Converter<JsonNode, Json> {

    private final ObjectMapper objectMapper;

    @Override
    public Json convert(JsonNode source) {
        try {
            return Json.of(objectMapper.writeValueAsString(source));
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Failed to serialize JsonNode to jsonb", e);
        }
    }
}
