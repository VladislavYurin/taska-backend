package ru.taska.service;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
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
    }

    @Test
    void shouldMaskSensitiveColumns() {
        // given
        Mockito.when(tableProps.sensitiveColumns()).thenReturn(List.of(TEST_TABLE + ".email", TEST_TABLE + ".phone"));

        List<Map<String, Object>> rows = List.of(
                Map.of("id", "123", "email", "test@example.com", "phone", "+79161234567")
        );

        // when
        List<Map<String, Object>> maskedRows = maskService.maskSensitiveColumns(rows, TEST_SERVICE, TEST_TABLE);

        // then
        Assertions.assertThat(maskedRows).hasSize(1);
        Map<String, Object> row = maskedRows.getFirst();
        Assertions.assertThat(row.get("email")).isEqualTo("***");
        Assertions.assertThat(row.get("phone")).isEqualTo("***");
        Assertions.assertThat(row.get("id")).isEqualTo("123");
    }

    @Test
    void shouldNotMaskNonSensitiveColumns() {
        // given
        Mockito.when(tableProps.sensitiveColumns()).thenReturn(List.of(TEST_TABLE + ".email"));

        List<Map<String, Object>> rows = List.of(
                Map.of("id", "123", "status", "active")
        );

        // when
        List<Map<String, Object>> maskedRows = maskService.maskSensitiveColumns(rows, TEST_SERVICE, TEST_TABLE);

        // then
        Assertions.assertThat(maskedRows).hasSize(1);
        Map<String, Object> row = maskedRows.getFirst();
        Assertions.assertThat(row.get("id")).isEqualTo("123");
        Assertions.assertThat(row.get("status")).isEqualTo("active");
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
        List<Map<String, Object>> maskedRows = maskService.maskSensitiveColumns(rows, TEST_SERVICE, TEST_TABLE);

        // then
        Assertions.assertThat(maskedRows).hasSize(1);
        Map<String, Object> result = maskedRows.getFirst();
        // null заменяется на "***"
        Assertions.assertThat(result.get("email")).isEqualTo("***");
        Assertions.assertThat(result.get("id")).isEqualTo("123");
    }

    @Test
    void shouldHandleEmptyRows() {
        // given
        List<Map<String, Object>> rows = List.of();

        // when
        List<Map<String, Object>> maskedRows = maskService.maskSensitiveColumns(rows, TEST_SERVICE, TEST_TABLE);

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
        List<Map<String, Object>> maskedRows = maskService.maskSensitiveColumns(rows, TEST_SERVICE, TEST_TABLE);

        // then
        Assertions.assertThat(maskedRows).hasSize(1);
        Map<String, Object> row = maskedRows.getFirst();
        Assertions.assertThat(row.get("id")).isEqualTo("123");
        Assertions.assertThat(row.get("status")).isEqualTo("active");
    }

    @Test
    void shouldHandleEmptySensitiveColumns() {
        // given
        Mockito.when(tableProps.sensitiveColumns()).thenReturn(List.of());

        List<Map<String, Object>> rows = List.of(
                Map.of("id", "123", "status", "active")
        );

        // when
        List<Map<String, Object>> maskedRows = maskService.maskSensitiveColumns(rows, TEST_SERVICE, TEST_TABLE);

        // then
        Assertions.assertThat(maskedRows).hasSize(1);
        Map<String, Object> row = maskedRows.getFirst();
        Assertions.assertThat(row.get("id")).isEqualTo("123");
        Assertions.assertThat(row.get("status")).isEqualTo("active");
    }
}