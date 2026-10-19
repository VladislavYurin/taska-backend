package mapper;

import io.grpc.Metadata;
import tools.jackson.core.json.JsonWriteFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/** Shared wire format for optional machine-readable domain error details. */
public final class GrpcErrorDetails {
    public static final Metadata.Key<String> KEY = Metadata.Key.of(
            "taska-error-details", Metadata.ASCII_STRING_MARSHALLER);
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(JsonWriteFeature.ESCAPE_NON_ASCII).build();

    private GrpcErrorDetails() {
    }

    public static Metadata encode(Map<String, String> details) {
        Metadata trailers = new Metadata();
        if (!details.isEmpty()) {
            trailers.put(KEY, JSON.writeValueAsString(details));
        }
        return trailers;
    }

    public static Map<String, String> decode(Metadata trailers) {
        String value = trailers == null ? null : trailers.get(KEY);
        if (value == null) {
            return Map.of();
        }
        try {
            JsonNode root = JSON.readTree(value);
            if (root == null || !root.isObject()) {
                return Map.of();
            }
            Map<String, String> details = new LinkedHashMap<>();
            for (var entry : root.properties()) {
                if (!entry.getValue().isString()) {
                    return Map.of();
                }
                details.put(entry.getKey(), entry.getValue().asString());
            }
            return Map.copyOf(details);
        } catch (tools.jackson.core.JacksonException e) {
            return Map.of();
        }
    }
}
