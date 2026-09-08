package ru.taska.mapper;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import ru.taska.api.admin.v1.ListTableRowsResponse;
import ru.taska.api.admin.v1.Value;
import ru.taska.dto.ListTableRowsResponseDto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

class ListTableRowsMapperTest {

    private static final String TEST_SERVICE = "test-service";
    private static final String TEST_TABLE = "test_table";

    private final ListTableRowsMapper mapper = new ListTableRowsMapper();

    @Test
    void shouldConvertStringToGrpcValue() {
        Value result = mapper.toGrpcValue("test");
        Assertions.assertThat(result.getStringValue()).isEqualTo("test");
    }

    @Test
    void shouldConvertIntegerToGrpcValue() {
        Value result = mapper.toGrpcValue(123);
        Assertions.assertThat(result.getIntValue()).isEqualTo(123);
    }

    @Test
    void shouldConvertLongToGrpcValue() {
        Value result = mapper.toGrpcValue(123L);
        Assertions.assertThat(result.getIntValue()).isEqualTo(123L);
    }

    @Test
    void shouldConvertShortToGrpcValue() {
        Value result = mapper.toGrpcValue((short) 7);
        Assertions.assertThat(result.getIntValue()).isEqualTo(7L);
    }

    @Test
    void shouldConvertDoubleToGrpcValue() {
        Value result = mapper.toGrpcValue(123.45);
        Assertions.assertThat(result.getDoubleValue()).isEqualTo(123.45);
    }

    @Test
    void shouldConvertFloatToGrpcValue() {
        Value result = mapper.toGrpcValue(1.5f);
        Assertions.assertThat(result.getDoubleValue()).isEqualTo(1.5);
    }

    @Test
    void shouldConvertBigDecimalToGrpcValue() {
        Value result = mapper.toGrpcValue(new BigDecimal("42.5"));
        Assertions.assertThat(result.getDoubleValue()).isEqualTo(42.5);
    }

    @Test
    void shouldConvertBooleanToGrpcValue() {
        Value result = mapper.toGrpcValue(true);
        Assertions.assertThat(result.getBoolValue()).isTrue();
    }

    @Test
    void shouldConvertNullToGrpcValue() {
        Value result = mapper.toGrpcValue(null);
        Assertions.assertThat(result.getNullValue()).isTrue();
    }

    @Test
    void shouldConvertInstantToGrpcValue() {
        Instant instant = Instant.ofEpochSecond(1_700_000_000L);
        Value result = mapper.toGrpcValue(instant);
        Assertions.assertThat(result.getTimestampValue()).isEqualTo(1_700_000_000L);
    }

    @Test
    void shouldConvertOffsetDateTimeToGrpcValue() {
        OffsetDateTime odt = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        Value result = mapper.toGrpcValue(odt);
        Assertions.assertThat(result.getTimestampValue()).isEqualTo(odt.toEpochSecond());
    }

    @Test
    void shouldConvertLocalDateTimeToGrpcValueAsUtc() {
        LocalDateTime ldt = LocalDateTime.of(2026, 6, 15, 12, 0, 0);
        Value result = mapper.toGrpcValue(ldt);
        Assertions.assertThat(result.getTimestampValue())
                .isEqualTo(ldt.toEpochSecond(ZoneOffset.UTC));
    }

    @Test
    void shouldConvertUnknownTypeToStringViaToString() {
        UUID id = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        Value result = mapper.toGrpcValue(id);
        Assertions.assertThat(result.getStringValue()).isEqualTo(id.toString());
    }

    @ParameterizedTest
    @MethodSource("paginationCases")
    void shouldComputePaginationMeta(
            int page,
            int pageSize,
            long total,
            int expectedTotalPages,
            boolean expectedHasPrev,
            boolean expectedHasNext
    ) {
        ListTableRowsResponseDto dto = new ListTableRowsResponseDto(
                List.of(),
                total,
                page,
                pageSize,
                List.of("id"),
                TEST_SERVICE,
                TEST_TABLE
        );

        ListTableRowsResponse response = mapper.toListTableRowsResponse(dto);

        Assertions.assertThat(response.getPagination().getCurrentPage()).isEqualTo(page);
        Assertions.assertThat(response.getPagination().getPageSize()).isEqualTo(pageSize);
        Assertions.assertThat(response.getPagination().getTotalRows()).isEqualTo(total);
        Assertions.assertThat(response.getPagination().getTotalPages()).isEqualTo(expectedTotalPages);
        Assertions.assertThat(response.getPagination().getHasPrev()).isEqualTo(expectedHasPrev);
        Assertions.assertThat(response.getPagination().getHasNext()).isEqualTo(expectedHasNext);
    }

    private static Stream<Arguments> paginationCases() {
        return Stream.of(
                Arguments.of(0, 20, 100L, 5, false, true),
                Arguments.of(2, 20, 100L, 5, true, true),
                Arguments.of(4, 20, 100L, 5, true, false),
                Arguments.of(0, 20, 0L, 0, false, false)
        );
    }
}
