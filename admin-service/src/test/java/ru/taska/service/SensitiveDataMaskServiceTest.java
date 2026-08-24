package ru.taska.service;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import io.r2dbc.postgresql.codec.Json;
import ru.taska.config.props.MaskType;
import ru.taska.config.props.MetadataCatalogProperties;
import ru.taska.exception.DomainException;
import ru.taska.service.readonly.SensitiveDataMaskService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ExtendWith(MockitoExtension.class)
class SensitiveDataMaskServiceTest {

    private static final String TEST_SERVICE = "test-service";
    private static final String TEST_TABLE = "test_table";

    @Mock
    private MetadataCatalogProperties properties;

    @Mock
    private MetadataCatalogProperties.ServiceProperties serviceProps;

    @Mock
    private MetadataCatalogProperties.TableProperties tableProps;

    @InjectMocks
    private SensitiveDataMaskService maskService;

    @BeforeEach
    void setUp() {
        Mockito.lenient().when(properties.services()).thenReturn(Map.of(TEST_SERVICE, serviceProps));
        Mockito.lenient().when(serviceProps.tables()).thenReturn(tableProps);
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
        List<Map<String, Object>> maskedRows = maskService.maskSensitiveData(rows, TEST_SERVICE, TEST_TABLE, "test-request-id", "test-node-id");

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
        List<Map<String, Object>> maskedRows = maskService.maskSensitiveData(rows, TEST_SERVICE, TEST_TABLE, "test-request-id", "test-node-id");

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
        List<Map<String, Object>> maskedRows = maskService.maskSensitiveData(rows, TEST_SERVICE, TEST_TABLE, "test-request-id", "test-node-id");

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
        List<Map<String, Object>> maskedRows = maskService.maskSensitiveData(rows, TEST_SERVICE, TEST_TABLE, "test-request-id", "test-node-id");

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
        List<Map<String, Object>> maskedRows = maskService.maskSensitiveData(rows, TEST_SERVICE, TEST_TABLE, "test-request-id", "test-node-id");

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
        List<Map<String, Object>> maskedRows = maskService.maskSensitiveData(rows, TEST_SERVICE, TEST_TABLE, "test-request-id", "test-node-id");

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
        List<Map<String, Object>> maskedRows = maskService.maskSensitiveData(rows, TEST_SERVICE, TEST_TABLE, "test-request-id", "test-node-id");

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
        List<Map<String, Object>> maskedRows = maskService.maskSensitiveData(rows, TEST_SERVICE, TEST_TABLE, "test-request-id", "test-node-id");

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
        List<Map<String, Object>> maskedRows = maskService.maskSensitiveData(rows, TEST_SERVICE, TEST_TABLE, "test-request-id", "test-node-id");

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

        List<Map<String, Object>> maskedRows = maskService.maskSensitiveData(rows, TEST_SERVICE, TEST_TABLE, "test-request-id", "test-node-id");

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
        List<Map<String, Object>> maskedRows = maskService.maskSensitiveData(rows, TEST_SERVICE, TEST_TABLE, "test-request-id", "test-node-id");

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
        List<Map<String, Object>> maskedRows = maskService.maskSensitiveData(rows, TEST_SERVICE, TEST_TABLE, "test-request-id", "test-node-id");

        // then
        Assertions.assertThat(maskedRows.getFirst().get("code")).isEqualTo("***");
    }

    // ==================== JSON field masking ====================

    @Test
    void shouldMaskJsonFieldsPartial() {
        // given
        Mockito.when(tableProps.sensitiveColumns()).thenReturn(Map.of());
        Mockito.when(tableProps.sensitiveJsonFields()).thenReturn(Map.of(
                TEST_TABLE, Map.of("payload", Map.of("email", "MASK_PARTIAL"))
        ));

        List<Map<String, Object>> rows = List.of(
                Map.of("id", "123", "payload", "{\"email\":\"test@example.com\",\"name\":\"John\"}")
        );

        // when
        List<Map<String, Object>> maskedRows = maskService.maskSensitiveData(rows, TEST_SERVICE, TEST_TABLE, "test-request-id", "test-node-id");

        // then
        Assertions.assertThat(maskedRows).hasSize(1);
        String payload = (String) maskedRows.getFirst().get("payload");
        Assertions.assertThat(payload).contains("\"name\":\"John\"");
        Assertions.assertThat(payload).contains("\"email\":\"t**************m\"");
        Assertions.assertThat(payload).doesNotContain("test@example.com");
    }

    @Test
    void shouldHideJsonFields() {
        // given
        Mockito.when(tableProps.sensitiveColumns()).thenReturn(Map.of());
        Mockito.when(tableProps.sensitiveJsonFields()).thenReturn(Map.of(
                TEST_TABLE, Map.of("payload", Map.of("password", "HIDE"))
        ));

        List<Map<String, Object>> rows = List.of(
                Map.of("id", "123", "payload", "{\"email\":\"test@example.com\",\"password\":\"secret123\"}")
        );

        // when
        List<Map<String, Object>> maskedRows = maskService.maskSensitiveData(rows, TEST_SERVICE, TEST_TABLE, "test-request-id", "test-node-id");

        // then
        String payload = (String) maskedRows.getFirst().get("payload");
        Assertions.assertThat(payload).contains("\"email\":\"test@example.com\"");
        Assertions.assertThat(payload).doesNotContain("password");
        Assertions.assertThat(payload).doesNotContain("secret123");
    }

    @Test
    void shouldFullMaskJsonFields() {
        // given
        Mockito.when(tableProps.sensitiveColumns()).thenReturn(Map.of());
        Mockito.when(tableProps.sensitiveJsonFields()).thenReturn(Map.of(
                TEST_TABLE, Map.of("payload", Map.of("email", "MASK_FULL"))
        ));

        List<Map<String, Object>> rows = List.of(
                Map.of("id", "123", "payload", "{\"email\":\"test@example.com\",\"name\":\"John\"}")
        );

        // when
        List<Map<String, Object>> maskedRows = maskService.maskSensitiveData(rows, TEST_SERVICE, TEST_TABLE, "test-request-id", "test-node-id");

        // then
        String payload = (String) maskedRows.getFirst().get("payload");
        Assertions.assertThat(payload).contains("\"email\":\"***\"");
        Assertions.assertThat(payload).contains("\"name\":\"John\"");
    }

    @Test
    void shouldMaskJsonFieldsInNestedObjects() {
        // given
        Mockito.when(tableProps.sensitiveColumns()).thenReturn(Map.of());
        Mockito.when(tableProps.sensitiveJsonFields()).thenReturn(Map.of(
                TEST_TABLE, Map.of("payload", Map.of("email", "MASK_PARTIAL"))
        ));

        List<Map<String, Object>> rows = List.of(
                Map.of("id", "123", "payload", "{\"user\":{\"email\":\"test@example.com\"},\"action\":\"create\"}")
        );

        // when
        List<Map<String, Object>> maskedRows = maskService.maskSensitiveData(rows, TEST_SERVICE, TEST_TABLE, "test-request-id", "test-node-id");

        // then
        String payload = (String) maskedRows.getFirst().get("payload");
        Assertions.assertThat(payload).contains("\"action\":\"create\"");
        Assertions.assertThat(payload).doesNotContain("test@example.com");
    }

    @Test
    void shouldThrowExceptionOnInvalidJson() {
        // given
        Mockito.when(tableProps.sensitiveColumns()).thenReturn(Map.of());
        Mockito.when(tableProps.sensitiveJsonFields()).thenReturn(Map.of(
                TEST_TABLE, Map.of("payload", Map.of("email", "MASK_FULL"))
        ));

        List<Map<String, Object>> rows = List.of(
                Map.of("id", "123", "payload", "not-a-json")
        );

        // when / then
        Assertions.assertThatThrownBy(() ->
                        maskService.maskSensitiveData(rows, TEST_SERVICE, TEST_TABLE, "test-request-id", "test-node-id"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("Cannot mask JSON fields");
    }

    @Test
    void shouldHandleNoSensitiveJsonFields() {
        // given
        Mockito.when(tableProps.sensitiveColumns()).thenReturn(Map.of());
        Mockito.when(tableProps.sensitiveJsonFields()).thenReturn(null);

        List<Map<String, Object>> rows = List.of(
                Map.of("id", "123", "payload", "{\"email\":\"test@example.com\"}")
        );

        // when
        List<Map<String, Object>> maskedRows = maskService.maskSensitiveData(rows, TEST_SERVICE, TEST_TABLE, "test-request-id", "test-node-id");

        // then — ничего не замаскировано
        Assertions.assertThat(maskedRows.getFirst().get("payload")).isEqualTo("{\"email\":\"test@example.com\"}");
    }

    @Test
    void shouldUseDefaultMaskTypeForJsonFields() {
        // given — значение null для типа маскировки JSON-поля
        Mockito.when(tableProps.sensitiveColumns()).thenReturn(Map.of());
        Map<String, String> jsonFields = new HashMap<>();
        jsonFields.put("email", null);
        Mockito.when(tableProps.sensitiveJsonFields()).thenReturn(Map.of(
                TEST_TABLE, Map.of("payload", jsonFields)
        ));
        Mockito.when(properties.defaultMaskType()).thenReturn(MaskType.MASK_FULL);

        List<Map<String, Object>> rows = List.of(
                Map.of("id", "123", "payload", "{\"email\":\"test@example.com\"}")
        );

        // when
        List<Map<String, Object>> maskedRows = maskService.maskSensitiveData(rows, TEST_SERVICE, TEST_TABLE, "test-request-id", "test-node-id");

        // then — используется дефолтный тип (MASK_FULL)
        String payload = (String) maskedRows.getFirst().get("payload");
        Assertions.assertThat(payload).contains("\"email\":\"***\"");
    }

    @Test
    void shouldApplyBothColumnAndJsonFieldMasking() {
        // given — колонка email маскируется целиком, а в payload маскируется поле email
        Mockito.when(tableProps.sensitiveColumns()).thenReturn(Map.of(
                TEST_TABLE + ".email", "MASK_FULL"
        ));
        Mockito.when(tableProps.sensitiveJsonFields()).thenReturn(Map.of(
                TEST_TABLE, Map.of("payload", Map.of("email", "MASK_PARTIAL"))
        ));

        List<Map<String, Object>> rows = List.of(
                Map.of("id", "123", "email", "direct@example.com",
                        "payload", "{\"email\":\"nested@example.com\",\"status\":\"active\"}")
        );

        // when
        List<Map<String, Object>> maskedRows = maskService.maskSensitiveData(rows, TEST_SERVICE, TEST_TABLE, "test-request-id", "test-node-id");

        // then
        Map<String, Object> row = maskedRows.getFirst();
        Assertions.assertThat(row.get("email")).isEqualTo("***");
        String payload = (String) row.get("payload");
        Assertions.assertThat(payload).contains("\"status\":\"active\"");
        Assertions.assertThat(payload).doesNotContain("nested@example.com");
    }

    @Test
    void shouldHandleJsonColumnWithR2dbcJsonType() {
        // given
        Mockito.when(tableProps.sensitiveColumns()).thenReturn(Map.of());
        Mockito.when(tableProps.sensitiveJsonFields()).thenReturn(Map.of(
                TEST_TABLE, Map.of("payload", Map.of("email", "MASK_FULL"))
        ));

        Json jsonValue = Json.of("{\"email\":\"test@example.com\",\"name\":\"John\"}");
        List<Map<String, Object>> rows = List.of(
                Map.of("id", "123", "payload", jsonValue)
        );

        // when
        List<Map<String, Object>> maskedRows = maskService.maskSensitiveData(rows, TEST_SERVICE, TEST_TABLE, "test-request-id", "test-node-id");

        // then
        Object payload = maskedRows.getFirst().get("payload");
        Assertions.assertThat(payload).isInstanceOf(Json.class);
        String payloadStr = ((Json) payload).asString();
        Assertions.assertThat(payloadStr).contains("\"email\":\"***\"");
        Assertions.assertThat(payloadStr).contains("\"name\":\"John\"");
    }

    @Test
    void shouldThrowExceptionWhenJsonColumnHasUnsupportedType() {
        // given
        Mockito.when(tableProps.sensitiveColumns()).thenReturn(Map.of());
        Mockito.when(tableProps.sensitiveJsonFields()).thenReturn(Map.of(
                TEST_TABLE, Map.of("payload", Map.of("email", "MASK_FULL"))
        ));

        List<Map<String, Object>> rows = List.of(
                Map.of("id", "123", "payload", 42)
        );

        // when / then
        Assertions.assertThatThrownBy(() ->
                        maskService.maskSensitiveData(rows, TEST_SERVICE, TEST_TABLE, "test-request-id", "test-node-id"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("expected Json or String type");
    }

    @Test
    void shouldReturnBlankJsonStringAsIs() {
        // given
        Mockito.when(tableProps.sensitiveColumns()).thenReturn(Map.of());
        Mockito.when(tableProps.sensitiveJsonFields()).thenReturn(Map.of(
                TEST_TABLE, Map.of("payload", Map.of("email", "MASK_FULL"))
        ));

        List<Map<String, Object>> rows = List.of(
                Map.of("id", "123", "payload", "   ")
        );

        // when
        List<Map<String, Object>> maskedRows = maskService.maskSensitiveData(rows, TEST_SERVICE, TEST_TABLE, "test-request-id", "test-node-id");

        // then — пустая строка возвращается без изменений
        Assertions.assertThat(maskedRows.getFirst().get("payload")).isEqualTo("   ");
    }

    @Test
    void shouldMaskNullJsonFieldValueAsFullMask() {
        // given — JSON-поле со значением null при MASK_PARTIAL маскируется полностью
        Mockito.when(tableProps.sensitiveColumns()).thenReturn(Map.of());
        Mockito.when(tableProps.sensitiveJsonFields()).thenReturn(Map.of(
                TEST_TABLE, Map.of("payload", Map.of("email", "MASK_PARTIAL"))
        ));

        List<Map<String, Object>> rows = List.of(
                Map.of("id", "123", "payload", "{\"email\":null,\"name\":\"John\"}")
        );

        // when
        List<Map<String, Object>> maskedRows = maskService.maskSensitiveData(rows, TEST_SERVICE, TEST_TABLE, "test-request-id", "test-node-id");

        // then
        String payload = (String) maskedRows.getFirst().get("payload");
        Assertions.assertThat(payload).contains("\"email\":\"***\"");
        Assertions.assertThat(payload).contains("\"name\":\"John\"");
    }

    // ==================== Configuration validation ====================

    @Test
    void shouldThrowExceptionWhenServicesNotConfigured() {
        // given — properties.services() возвращает null
        Mockito.reset(properties);
        Mockito.when(properties.services()).thenReturn(null);

        List<Map<String, Object>> rows = List.of(
                Map.of("id", "123")
        );

        // when / then
        Assertions.assertThatThrownBy(() ->
                        maskService.maskSensitiveData(rows, TEST_SERVICE, TEST_TABLE, "test-request-id", "test-node-id"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("services");
    }

    @Test
    void shouldThrowExceptionWhenServiceKeyNotConfigured() {
        // given — сервис отсутствует в конфигурации
        List<Map<String, Object>> rows = List.of(
                Map.of("id", "123")
        );

        // when / then
        Assertions.assertThatThrownBy(() ->
                        maskService.maskSensitiveData(rows, "unknown-service", TEST_TABLE, "test-request-id", "test-node-id"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("unknown-service");
    }

    @Test
    void shouldThrowExceptionWhenTablesNotConfigured() {
        // given — serviceProps.tables() возвращает null
        Mockito.reset(serviceProps);
        Mockito.when(serviceProps.tables()).thenReturn(null);

        List<Map<String, Object>> rows = List.of(
                Map.of("id", "123")
        );

        // when / then
        Assertions.assertThatThrownBy(() ->
                        maskService.maskSensitiveData(rows, TEST_SERVICE, TEST_TABLE, "test-request-id", "test-node-id"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("tables");
    }

    @Test
    void shouldThrowExceptionWhenMaskTypeIsUnknown() {
        // given — неизвестный тип маскировки
        Mockito.when(tableProps.sensitiveColumns()).thenReturn(Map.of(
                TEST_TABLE + ".email", "UNKNOWN_TYPE"
        ));

        List<Map<String, Object>> rows = List.of(
                Map.of("id", "123", "email", "test@example.com")
        );

        // when / then
        Assertions.assertThatThrownBy(() ->
                        maskService.maskSensitiveData(rows, TEST_SERVICE, TEST_TABLE, "test-request-id", "test-node-id"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("unknown mask type");
    }

    @Test
    void shouldUseDefaultMaskTypeWhenBlankString() {
        // given — пустая строка (не null) для типа маскировки
        Map<String, String> columns = new HashMap<>();
        columns.put(TEST_TABLE + ".email", "   ");
        Mockito.when(tableProps.sensitiveColumns()).thenReturn(columns);
        Mockito.when(properties.defaultMaskType()).thenReturn(MaskType.MASK_FULL);

        List<Map<String, Object>> rows = List.of(
                Map.of("id", "123", "email", "test@example.com")
        );

        // when
        List<Map<String, Object>> maskedRows = maskService.maskSensitiveData(rows, TEST_SERVICE, TEST_TABLE, "test-request-id", "test-node-id");

        // then — используется дефолтный тип (MASK_FULL)
        Assertions.assertThat(maskedRows.getFirst().get("email")).isEqualTo("***");
    }

    @Test
    void shouldThrowExceptionWhenSensitiveJsonFieldsValueIsNull() {
        // given — в конфиге sensitiveJsonFields для колонки задан null вместо map полей
        Mockito.when(tableProps.sensitiveColumns()).thenReturn(Map.of());
        Map<String, Map<String, String>> columnsConfig = new HashMap<>();
        columnsConfig.put("payload", null);
        Mockito.when(tableProps.sensitiveJsonFields()).thenReturn(Map.of(
                TEST_TABLE, columnsConfig
        ));

        List<Map<String, Object>> rows = List.of(
                Map.of("id", "123", "payload", "{\"email\":\"test@example.com\"}")
        );

        // when / then
        Assertions.assertThatThrownBy(() ->
                        maskService.maskSensitiveData(rows, TEST_SERVICE, TEST_TABLE, "test-request-id", "test-node-id"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("sensitiveJsonFields value is null");
    }

    @Test
    void shouldThrowExceptionWhenJsonIsNotObject() {
        // given
        Mockito.when(tableProps.sensitiveColumns()).thenReturn(Map.of());
        Mockito.when(tableProps.sensitiveJsonFields()).thenReturn(Map.of(
                TEST_TABLE, Map.of("payload", Map.of("email", "MASK_FULL"))
        ));

        List<Map<String, Object>> rows = List.of(
                Map.of("id", "123", "payload", "[1, 2, 3]")
        );

        // when / then
        Assertions.assertThatThrownBy(() ->
                        maskService.maskSensitiveData(rows, TEST_SERVICE, TEST_TABLE, "test-request-id", "test-node-id"))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("Cannot mask JSON fields");
    }
}
