package ru.taska.config.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.postgresql.codec.Json;
import lombok.RequiredArgsConstructor;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.stereotype.Component;

@ReadingConverter
@RequiredArgsConstructor
@Component
public class JsonBToJsonNodeConverter implements Converter<Json, JsonNode> {
    private final ObjectMapper objectMapper;

    @Override
    public JsonNode convert(Json source) {
        try {
            return objectMapper.readTree(source.asString());
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}
