package ru.taska.mapper;

import lombok.experimental.UtilityClass;
import ru.taska.api.common.v1.Header;
import ru.taska.domain.GatewayContext;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

@UtilityClass
public class MappingUtils {

    public static Header buildGrpcHeader(GatewayContext context) {
        var builder = Header.newBuilder().setRequestId(context.requestId()).setNodeId(context.nodeId());
        if (context.channel() != null) builder.setActionChannel(context.channel().name());
        if (context.userContext() != null && context.userContext().tokenKind() != ru.taska.domain.GatewayTokenKind.JWT
                && context.userContext().tokenId() != null) builder.setTokenId(context.userContext().tokenId());
        return builder.build();
    }

    /**
     * Устанавливает значение в билдер, если строка не null и не пустая.
     */
    public static void setIfPresent(String value, Consumer<String> setter) {
        if (value != null && !value.isBlank()) {
            setter.accept(value);
        }
    }

    /**
     * Устанавливает значение в билдер, если объект не null.
     */
    public static <T> void setIfPresent(T value, Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }

    /**
     * Устанавливает значение с преобразованием, если строка не null и не пустая.
     */
    public static <T> void setIfPresent(String value, Function<String, T> converter, Consumer<T> setter) {
        if (value != null && !value.isBlank()) {
            setter.accept(converter.apply(value));
        }
    }

    /**
     * Устанавливает значение в билдер, если объект есть в protoDto.
     */
    public static  <T> void setIfPresent(BooleanSupplier hasCheck, Supplier<T> getter, Consumer<T> setter) {
        if (hasCheck.getAsBoolean()) {
            setter.accept(getter.get());
        }
    }

    /**
     * Устанавливает значение с преобразованием, если объект не null.
     */
    public static <T, R> void setIfPresent(T value, Function<T, R> converter, Consumer<R> setter) {
        if (value != null) {
            setter.accept(converter.apply(value));
        }
    }

    public static OffsetDateTime toOffsetDateTime(com.google.protobuf.Timestamp timestamp) {
        if (timestamp == null || (timestamp.getSeconds() == 0 && timestamp.getNanos() == 0)) {
            return null;
        }

        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos())
                .atOffset(ZoneOffset.UTC);
    }
}
