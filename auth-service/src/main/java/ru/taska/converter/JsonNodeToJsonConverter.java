package ru.taska.converter;

import com.fasterxml.jackson.databind.JsonNode;
import io.r2dbc.postgresql.codec.Json;
import lombok.RequiredArgsConstructor;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.lang.NonNull;

@WritingConverter
@RequiredArgsConstructor
public class JsonNodeToJsonConverter implements Converter<JsonNode, Json> {

    @Override
    public Json convert(@NonNull JsonNode source) {
        return Json.of(source.toString());
    }
}
