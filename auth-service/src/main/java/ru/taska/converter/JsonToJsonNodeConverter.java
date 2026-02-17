package ru.taska.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.postgresql.codec.Json;
import lombok.RequiredArgsConstructor;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.lang.NonNull;

@ReadingConverter
@RequiredArgsConstructor
public class JsonToJsonNodeConverter implements Converter<Json, JsonNode> {

    private final ObjectMapper objectMapper;

    @Override
    public JsonNode convert(@NonNull Json source) {
        try {
            return objectMapper.readTree(source.asString());
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid jsonb", e);
        }
    }
}
