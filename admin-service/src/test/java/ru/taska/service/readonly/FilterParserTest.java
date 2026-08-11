package ru.taska.service.readonly;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import ru.taska.dto.FilterOperatorsDto;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;

import java.util.LinkedHashMap;
import java.util.Map;

class FilterParserTest {

    private final FilterParser parser = new FilterParser();

    @Test
    void parse_nullOrEmpty_returnsEmptyMap() {
        Assertions.assertThat(parser.parse(null)).isEmpty();
        Assertions.assertThat(parser.parse(Map.of())).isEmpty();
    }

    @Test
    void parse_equalsOperator() {
        Map<String, FilterOperatorsDto> result = parser.parse(Map.of("status.equals", "active"));

        Assertions.assertThat(result).containsOnlyKeys("status");
        Assertions.assertThat(result.get("status").equals()).isEqualTo("active");
        Assertions.assertThat(result.get("status").contains()).isNull();
        Assertions.assertThat(result.get("status").from()).isNull();
        Assertions.assertThat(result.get("status").to()).isNull();
    }

    @Test
    void parse_containsOperator() {
        Map<String, FilterOperatorsDto> result = parser.parse(Map.of("email.contains", "@x"));

        Assertions.assertThat(result.get("email").contains()).isEqualTo("@x");
        Assertions.assertThat(result.get("email").equals()).isNull();
    }

    @Test
    void parse_fromAndToOnSameColumn() {
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("created_at.from", "2026-01-01T00:00:00Z");
        raw.put("created_at.to", "2026-12-31T23:59:59Z");

        Map<String, FilterOperatorsDto> result = parser.parse(raw);

        Assertions.assertThat(result).containsOnlyKeys("created_at");
        Assertions.assertThat(result.get("created_at").from()).isEqualTo("2026-01-01T00:00:00Z");
        Assertions.assertThat(result.get("created_at").to()).isEqualTo("2026-12-31T23:59:59Z");
    }

    @Test
    void parse_multipleColumns() {
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("status.equals", "active");
        raw.put("email.contains", "@test.com");

        Map<String, FilterOperatorsDto> result = parser.parse(raw);

        Assertions.assertThat(result).containsOnlyKeys("status", "email");
        Assertions.assertThat(result.get("status").equals()).isEqualTo("active");
        Assertions.assertThat(result.get("email").contains()).isEqualTo("@test.com");
    }

    @Test
    void parse_blankValue_throwsInvalidArgument() {
        Assertions.assertThatThrownBy(() -> parser.parse(Map.of("status.equals", "  ")))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("status", DomainStatus.INVALID_ARGUMENT)
                .hasMessageContaining("status.equals");
    }

    @Test
    void parse_keyWithoutOperator_throwsInvalidArgument() {
        Assertions.assertThatThrownBy(() -> parser.parse(Map.of("status", "active")))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("status", DomainStatus.INVALID_ARGUMENT)
                .hasMessageContaining("operator");
    }

    @Test
    void parse_unknownOperator_throwsInvalidArgument() {
        Assertions.assertThatThrownBy(() -> parser.parse(Map.of("status.neq", "active")))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("status", DomainStatus.INVALID_ARGUMENT)
                .hasMessageContaining("Unknown filter operator");
    }

    @Test
    void parse_columnNameWithDot_usesLastSegmentAsOperator() {
        Map<String, FilterOperatorsDto> result = parser.parse(Map.of("a.b.equals", "value"));

        Assertions.assertThat(result).containsOnlyKeys("a.b");
        Assertions.assertThat(result.get("a.b").equals()).isEqualTo("value");
    }
}
