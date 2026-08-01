package ru.taska.mapper;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import ru.taska.api.admin.v1.ListTableRowsRequest;
import ru.taska.api.admin.v1.ListTableRowsRequestBody;
import ru.taska.api.admin.v1.ListTableRowsResponse;
import ru.taska.api.admin.v1.Row;
import ru.taska.api.admin.v1.Value;
import ru.taska.api.common.v1.Header;
import ru.taska.config.props.MetadataCatalogProperties;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ExtendWith(MockitoExtension.class)
class ReadOnlyDataMapperTest {

    private static final String TEST_SERVICE = "test-service";
    private static final String TEST_TABLE = "test_table";

    private MetadataCatalogProperties properties;

    @Mock
    private MetadataCatalogProperties.TableProperties tableProps;

    @InjectMocks
    private ReadOnlyDataMapper mapper;

    @BeforeEach
    void setUp() {
        // Cоздаем реальный record с мокнутыми tableProps
        properties = new MetadataCatalogProperties(
                Map.of(TEST_SERVICE, new MetadataCatalogProperties.ServiceProperties(
                        "test-alias",
                        "taska",
                        tableProps
                ))
        );

        //Внедряем properties через рефлексию
        ReflectionTestUtils.setField(mapper, "properties", properties);
    }

    // ==================== ТЕСТЫ maskSensitiveColumns ====================

    @Test
    void shouldMaskSensitiveColumns() {
        // given
        Mockito.when(tableProps.sensitiveColumns()).thenReturn(List.of(TEST_TABLE + ".email", TEST_TABLE + ".phone"));

        List<Map<String, Object>> rows = List.of(
                Map.of("id", "123", "email", "test@example.com", "phone", "+79161234567")
        );

        // when
        List<Row> maskedRows = mapper.maskSensitiveColumns(rows, TEST_SERVICE, TEST_TABLE);

        // then
        Assertions.assertThat(maskedRows).hasSize(1);
        Map<String, Value> fields = maskedRows.getFirst().getFieldsMap();
        Assertions.assertThat(fields.get("email").getStringValue()).isEqualTo("***");
        Assertions.assertThat(fields.get("phone").getStringValue()).isEqualTo("***");
        Assertions.assertThat(fields.get("id").getStringValue()).isEqualTo("123");
    }

    @Test
    void shouldNotMaskNonSensitiveColumns() {
        // given
        Mockito.when(tableProps.sensitiveColumns()).thenReturn(List.of(TEST_TABLE + ".email"));

        List<Map<String, Object>> rows = List.of(
                Map.of("id", "123", "status", "active")
        );

        // when
        List<Row> maskedRows = mapper.maskSensitiveColumns(rows, TEST_SERVICE, TEST_TABLE);

        // then
        Assertions.assertThat(maskedRows).hasSize(1);
        Map<String, Value> fields = maskedRows.getFirst().getFieldsMap();
        Assertions.assertThat(fields.get("id").getStringValue()).isEqualTo("123");
        Assertions.assertThat(fields.get("status").getStringValue()).isEqualTo("active");
    }

    @Test
    void shouldHandleNullValuesInMasking() {
        // given
        Mockito.when(tableProps.sensitiveColumns()).thenReturn(List.of(TEST_TABLE + ".email"));

        Map<String, Object> row = new HashMap<>();
        row.put("id", "123");
        row.put("email", null);

        List<Map<String, Object>> rows = List.of(row);
        // when
        List<Row> maskedRows = mapper.maskSensitiveColumns(rows, TEST_SERVICE, TEST_TABLE);

        // then
        Assertions.assertThat(maskedRows).hasSize(1);
        Map<String, Value> fields = maskedRows.getFirst().getFieldsMap();
        Assertions.assertThat(fields.get("email").getNullValue()).isTrue();
    }

    @Test
    void shouldHandleEmptyRows() {
        // given
        List<Map<String, Object>> rows = List.of();

        // when
        List<Row> maskedRows = mapper.maskSensitiveColumns(rows, TEST_SERVICE, TEST_TABLE);

        // then
        Assertions.assertThat(maskedRows).isEmpty();
    }

    // ==================== ТЕСТЫ buildResponse ====================

    @Test
    void shouldBuildResponseWithAllFields() {
        // given
        ListTableRowsRequest request = ListTableRowsRequest.newBuilder()
                .setHeader(Header.newBuilder().setRequestId("test").setNodeId("test").build())
                .setBody(ListTableRowsRequestBody.newBuilder()
                        .setServiceKey(TEST_SERVICE)
                        .setTableName(TEST_TABLE)
                        .setPage(1)
                        .setPageSize(20)
                        .build())
                .build();

        List<Row> rows = List.of(
                Row.newBuilder()
                        .putFields("id", Value.newBuilder().setStringValue("123").build())
                        .putFields("status", Value.newBuilder().setStringValue("active").build())
                        .build()
        );

        Long total = 100L;
        List<String> allColumns = List.of("id", "status");

        // when
        ListTableRowsResponse response = mapper.buildResponse(request, rows, total, allColumns);

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
    }
}