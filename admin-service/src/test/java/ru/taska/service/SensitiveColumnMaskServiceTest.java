package ru.taska.service;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.taska.config.props.MaskType;
import ru.taska.config.props.MetadataCatalogProperties;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ExtendWith(MockitoExtension.class)
class SensitiveColumnMaskServiceTest {

    private static final String TEST_SERVICE = "test-service";
    private static final String TEST_TABLE = "test_table";

    @Mock
    private MetadataCatalogProperties properties;

    @Mock
    private MetadataCatalogProperties.ServiceProperties serviceProps;

    @Mock
    private MetadataCatalogProperties.TableProperties tableProps;

    @InjectMocks
    private SensitiveColumnMaskService maskService;

    @BeforeEach
    void setUp() {
        Mockito.when(properties.services()).thenReturn(Map.of(TEST_SERVICE, serviceProps));
        Mockito.when(serviceProps.tables()).thenReturn(tableProps);
        Mockito.lenient().when(properties.defaultMaskType()).thenReturn(MaskType.MASK_FULL);
    }

    @Test
    void shouldMaskFullSensitiveColumns() {
        // given
        Mockito.when(tableProps.sensitiveColumns()).thenReturn(Map.of(
                TEST_TABLE + ".email", "MASK_FULL",
                TEST_TABLE + ".phone", "MASK_FULL"
        ));

        List<Map<String, Object>> rows = List.of(
                Map.of("id", "123", "email", "test@example.com", "phone", "+79161234567")
        );

        // when
        List<Map<String, Object>> maskedRows = maskService.maskSensitiveColumns(rows, TEST_SERVICE, TEST_TABLE, "test-request-id", "test-node-id");

        // then
        Assertions.assertThat(maskedRows).hasSize(1);
        Map<String, Object> row = maskedRows.getFirst();
        Assertions.assertThat(row.get("email")).isEqualTo("***");
        Assertions.assertThat(row.get("phone")).isEqualTo("***");
        Assertions.assertThat(row.get("id")).isEqualTo("123");
    }

    @Test
    void shouldMaskPartialSensitiveColumns() {
        // given
        Mockito.when(tableProps.sensitiveColumns()).thenReturn(Map.of(
                TEST_TABLE + ".email", "MASK_PARTIAL"
        ));

        List<Map<String, Object>> rows = List.of(
                Map.of("id", "123", "email", "test@example.com")
        );

        // when
        List<Map<String, Object>> maskedRows = maskService.maskSensitiveColumns(rows, TEST_SERVICE, TEST_TABLE, "test-request-id", "test-node-id");

        // then
        Assertions.assertThat(maskedRows).hasSize(1);
        Map<String, Object> row = maskedRows.getFirst();
        Assertions.assertThat(row.get("email")).isEqualTo("t**************m");
        Assertions.assertThat(row.get("id")).isEqualTo("123");
    }

    @Test
    void shouldHideSensitiveColumns() {
        // given
        Mockito.when(tableProps.sensitiveColumns()).thenReturn(Map.of(
                TEST_TABLE + ".secret", "HIDE"
        ));

        List<Map<String, Object>> rows = List.of(
                Map.of("id", "123", "secret", "top-secret-value")
        );

        // when
        List<Map<String, Object>> maskedRows = maskService.maskSensitiveColumns(rows, TEST_SERVICE, TEST_TABLE, "test-request-id", "test-node-id");

        // then
        Assertions.assertThat(maskedRows).hasSize(1);
        Map<String, Object> row = maskedRows.getFirst();
        Assertions.assertThat(row).doesNotContainKey("secret");
        Assertions.assertThat(row.get("id")).isEqualTo("123");
    }

    @Test
    void shouldNotMaskNonSensitiveColumns() {
        // given
        Mockito.when(tableProps.sensitiveColumns()).thenReturn(Map.of(
                TEST_TABLE + ".email", "MASK_FULL"
        ));

        List<Map<String, Object>> rows = List.of(
                Map.of("id", "123", "status", "active")
        );

        // when
        List<Map<String, Object>> maskedRows = maskService.maskSensitiveColumns(rows, TEST_SERVICE, TEST_TABLE, "test-request-id", "test-node-id");

        // then
        Assertions.assertThat(maskedRows).hasSize(1);
        Map<String, Object> row = maskedRows.getFirst();
        Assertions.assertThat(row.get("id")).isEqualTo("123");
        Assertions.assertThat(row.get("status")).isEqualTo("active");
    }

    @Test
    void shouldHandleNullValuesInMasking() {
        // given
        Mockito.when(tableProps.sensitiveColumns()).thenReturn(Map.of(
                TEST_TABLE + ".email", "MASK_FULL"
        ));

        Map<String, Object> row = new HashMap<>();
        row.put("id", "123");
        row.put("email", null);

        List<Map<String, Object>> rows = List.of(row);

        // when
        List<Map<String, Object>> maskedRows = maskService.maskSensitiveColumns(rows, TEST_SERVICE, TEST_TABLE, "test-request-id", "test-node-id");

        // then
        Assertions.assertThat(maskedRows).hasSize(1);
        Map<String, Object> result = maskedRows.getFirst();
        // null заменяется на "***"
        Assertions.assertThat(result.get("email")).isEqualTo("***");
        Assertions.assertThat(result.get("id")).isEqualTo("123");
    }

    @Test
    void shouldHandleNullValuesInPartialMasking() {
        // given
        Mockito.when(tableProps.sensitiveColumns()).thenReturn(Map.of(
                TEST_TABLE + ".email", "MASK_PARTIAL"
        ));

        Map<String, Object> row = new HashMap<>();
        row.put("id", "123");
        row.put("email", null);

        List<Map<String, Object>> rows = List.of(row);

        // when
        List<Map<String, Object>> maskedRows = maskService.maskSensitiveColumns(rows, TEST_SERVICE, TEST_TABLE, "test-request-id", "test-node-id");

        // then
        Assertions.assertThat(maskedRows).hasSize(1);
        Map<String, Object> result = maskedRows.getFirst();
        Assertions.assertThat(result.get("email")).isEqualTo("***");
        Assertions.assertThat(result.get("id")).isEqualTo("123");
    }

    @Test
    void shouldHandleEmptyRows() {
        // given
        List<Map<String, Object>> rows = List.of();

        // when
        List<Map<String, Object>> maskedRows = maskService.maskSensitiveColumns(rows, TEST_SERVICE, TEST_TABLE, "test-request-id", "test-node-id");

        // then
        Assertions.assertThat(maskedRows).isEmpty();
    }

    @Test
    void shouldHandleNoSensitiveColumns() {
        // given
        Mockito.when(tableProps.sensitiveColumns()).thenReturn(null);

        List<Map<String, Object>> rows = List.of(
                Map.of("id", "123", "status", "active")
        );

        // when
        List<Map<String, Object>> maskedRows = maskService.maskSensitiveColumns(rows, TEST_SERVICE, TEST_TABLE, "test-request-id", "test-node-id");

        // then
        Assertions.assertThat(maskedRows).hasSize(1);
        Map<String, Object> row = maskedRows.getFirst();
        Assertions.assertThat(row.get("id")).isEqualTo("123");
        Assertions.assertThat(row.get("status")).isEqualTo("active");
    }

    @Test
    void shouldHandleEmptySensitiveColumns() {
        // given
        Mockito.when(tableProps.sensitiveColumns()).thenReturn(Map.of());

        List<Map<String, Object>> rows = List.of(
                Map.of("id", "123", "status", "active")
        );

        // when
        List<Map<String, Object>> maskedRows = maskService.maskSensitiveColumns(rows, TEST_SERVICE, TEST_TABLE, "test-request-id", "test-node-id");

        // then
        Assertions.assertThat(maskedRows).hasSize(1);
        Map<String, Object> row = maskedRows.getFirst();
        Assertions.assertThat(row.get("id")).isEqualTo("123");
        Assertions.assertThat(row.get("status")).isEqualTo("active");
    }

    @Test
    void shouldNotMaskColumnSensitiveOnOtherTable() {
        Mockito.when(tableProps.sensitiveColumns()).thenReturn(Map.of(
                "other_table.email", "MASK_FULL"
        ));

        List<Map<String, Object>> rows = List.of(
                Map.of("id", "123", "email", "test@example.com")
        );

        List<Map<String, Object>> maskedRows = maskService.maskSensitiveColumns(rows, TEST_SERVICE, TEST_TABLE, "test-request-id", "test-node-id");

        Assertions.assertThat(maskedRows.getFirst().get("email")).isEqualTo("test@example.com");
    }

    @Test
    void shouldUseDefaultMaskTypeWhenNotSpecified() {
        // given — значение null для типа маскировки
        Map<String, String> columns = new HashMap<>();
        columns.put(TEST_TABLE + ".email", null);
        Mockito.when(tableProps.sensitiveColumns()).thenReturn(columns);
        Mockito.when(properties.defaultMaskType()).thenReturn(MaskType.MASK_FULL);

        List<Map<String, Object>> rows = List.of(
                Map.of("id", "123", "email", "test@example.com")
        );

        // when
        List<Map<String, Object>> maskedRows = maskService.maskSensitiveColumns(rows, TEST_SERVICE, TEST_TABLE, "test-request-id", "test-node-id");

        // then
        Assertions.assertThat(maskedRows.getFirst().get("email")).isEqualTo("***");
    }

    @Test
    void shouldMaskPartialShortValues() {
        // given — значение ≤ 2 символов маскируется полностью
        Mockito.when(tableProps.sensitiveColumns()).thenReturn(Map.of(
                TEST_TABLE + ".code", "MASK_PARTIAL"
        ));

        List<Map<String, Object>> rows = List.of(
                Map.of("id", "123", "code", "ab")
        );

        // when
        List<Map<String, Object>> maskedRows = maskService.maskSensitiveColumns(rows, TEST_SERVICE, TEST_TABLE, "test-request-id", "test-node-id");

        // then
        Assertions.assertThat(maskedRows.getFirst().get("code")).isEqualTo("***");
    }
}
