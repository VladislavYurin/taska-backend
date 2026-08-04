package ru.taska.mapper;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.taska.api.admin.v1.ListTableRowsRequest;
import ru.taska.api.admin.v1.ListTableRowsRequestBody;
import ru.taska.api.admin.v1.ListTableRowsResponse;
import ru.taska.api.admin.v1.Row;
import ru.taska.api.admin.v1.Value;
import ru.taska.api.common.v1.Header;
import ru.taska.dto.ListTableRowsResponseDto;

import java.util.List;
import java.util.Map;

@ExtendWith(MockitoExtension.class)
class ListTableRowsMapperTest {

    private static final String TEST_SERVICE = "test-service";
    private static final String TEST_TABLE = "test_table";

    @InjectMocks
    private ListTableRowsMapper mapper;

    // ==================== ТЕСТЫ toGrpcValue ====================

    @Test
    void shouldConvertStringToGrpcValue() {
        // given
        String value = "test";

        // when
        Value result = mapper.toGrpcValue(value);

        // then
        Assertions.assertThat(result.getStringValue()).isEqualTo("test");
    }

    @Test
    void shouldConvertIntegerToGrpcValue() {
        // given
        Integer value = 123;

        // when
        Value result = mapper.toGrpcValue(value);

        // then
        Assertions.assertThat(result.getIntValue()).isEqualTo(123);
    }

    @Test
    void shouldConvertDoubleToGrpcValue() {
        // given
        Double value = 123.45;

        // when
        Value result = mapper.toGrpcValue(value);

        // then
        Assertions.assertThat(result.getDoubleValue()).isEqualTo(123.45);
    }

    @Test
    void shouldConvertBooleanToGrpcValue() {
        // given
        Boolean value = true;

        // when
        Value result = mapper.toGrpcValue(value);

        // then
        Assertions.assertThat(result.getBoolValue()).isTrue();
    }

    @Test
    void shouldConvertNullToGrpcValue() {
        // when
        Value result = mapper.toGrpcValue(null);

        // then
        Assertions.assertThat(result.getNullValue()).isTrue();
    }

    // ==================== ТЕСТЫ toRequestDto ====================

    @Test
    void shouldConvertProtoToRequestDto() {
        // given
        ListTableRowsRequest request = ListTableRowsRequest.newBuilder()
                .setHeader(Header.newBuilder().setRequestId("test").setNodeId("test").build())
                .setBody(ListTableRowsRequestBody.newBuilder()
                        .setServiceKey(TEST_SERVICE)
                        .setTableName(TEST_TABLE)
                        .setPage(1)
                        .setPageSize(20)
                        .setSort("created_at")
                        .setOrder("desc")
                        .build())
                .build();

        // when
        var dto = mapper.toRequestDto(request);

        // then
        Assertions.assertThat(dto.serviceKey()).isEqualTo(TEST_SERVICE);
        Assertions.assertThat(dto.tableName()).isEqualTo(TEST_TABLE);
        Assertions.assertThat(dto.page()).isEqualTo(1);
        Assertions.assertThat(dto.pageSize()).isEqualTo(20);
        Assertions.assertThat(dto.sort()).isEqualTo("created_at");
        Assertions.assertThat(dto.order()).isEqualTo("desc");
        Assertions.assertThat(dto.filters()).isEmpty();
    }

// ==================== ТЕСТЫ toListTableRowsResponse ====================

    @Test
    void shouldConvertResponseDtoToProto() {
        // given
        List<Map<String, Object>> rows = List.of(
                Map.of("id", "123", "status", "active")
        );

        ListTableRowsResponseDto dto = new ListTableRowsResponseDto(
                rows,
                100L,
                1,
                20,
                List.of("id", "status"),
                TEST_SERVICE,
                TEST_TABLE
        );

        // when
        ListTableRowsResponse response = mapper.toListTableRowsResponse(dto);

        // then
        Assertions.assertThat(response.getRowsCount()).isEqualTo(1);
        Assertions.assertThat(response.getPagination().getCurrentPage()).isEqualTo(1);
        Assertions.assertThat(response.getPagination().getPageSize()).isEqualTo(20);
        Assertions.assertThat(response.getPagination().getTotalRows()).isEqualTo(100);
        Assertions.assertThat(response.getPagination().getTotalPages()).isEqualTo(5);
        Assertions.assertThat(response.getPagination().getHasNext()).isTrue();
        Assertions.assertThat(response.getPagination().getHasPrev()).isFalse();
        Assertions.assertThat(response.getMeta().getServiceKey()).isEqualTo(TEST_SERVICE);
        Assertions.assertThat(response.getMeta().getTableName()).isEqualTo(TEST_TABLE);
        Assertions.assertThat(response.getMeta().getColumnsList()).containsExactly("id", "status");

        // Проверяем данные
        Row firstRow = response.getRows(0);
        Assertions.assertThat(firstRow.getFieldsMap().get("id").getStringValue()).isEqualTo("123");
        Assertions.assertThat(firstRow.getFieldsMap().get("status").getStringValue()).isEqualTo("active");
    }

    @Test
    void shouldHandleEmptyRowsInResponse() {
        // given
        List<Map<String, Object>> rows = List.of();

        ListTableRowsResponseDto dto = new ListTableRowsResponseDto(
                rows,
                0L,
                1,
                20,
                List.of("id", "status"),
                TEST_SERVICE,
                TEST_TABLE
        );

        // when
        ListTableRowsResponse response = mapper.toListTableRowsResponse(dto);

        // then
        Assertions.assertThat(response.getRowsList()).isEmpty();
        Assertions.assertThat(response.getPagination().getTotalRows()).isEqualTo(0);
        Assertions.assertThat(response.getPagination().getTotalPages()).isEqualTo(0);
        Assertions.assertThat(response.getPagination().getHasNext()).isFalse();
        Assertions.assertThat(response.getPagination().getHasPrev()).isFalse();
    }
}