package ru.taska.mapper;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import ru.taska.api.admin.v1.Catalog;
import ru.taska.api.admin.v1.ColumnMetadata;
import ru.taska.api.admin.v1.GetCatalogResponse;
import ru.taska.api.admin.v1.ListTableRowsResponse;
import ru.taska.api.admin.v1.MetaInfo;
import ru.taska.api.admin.v1.PaginationInfo;
import ru.taska.api.admin.v1.Row;
import ru.taska.api.admin.v1.ServiceMetadata;
import ru.taska.api.admin.v1.TableMetadata;
import ru.taska.api.admin.v1.Value;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

class AdminDataMapperTest {

    private static final String SERVICE_KEY = "test-service";
    private static final String SERVICE_ALIAS = "Test Service";
    private static final String TABLE_NAME = "test_table";
    private static final String COLUMN_NAME = "id";
    private static final String COLUMN_TYPE = "uuid";
    private static final String COLUMN_NAME_EMAIL = "email";
    private static final String COLUMN_TYPE_EMAIL = "varchar";

    private final AdminDataMapper mapper = new AdminDataMapper();

    // ==================== ТЕСТЫ GET CATALOG ====================

    @Test
    @DisplayName("Должен корректно преобразовать GetCatalogResponse в MetadataResponse")
    void toRestGetCatalogResponse_shouldCorrectMapsAllFields() {
        // given
        var serviceMetadata = ServiceMetadata.newBuilder()
                .setServiceKey(SERVICE_KEY)
                .setAlias(SERVICE_ALIAS)
                .addTables(TableMetadata.newBuilder()
                        .setName(TABLE_NAME)
                        .addColumns(ColumnMetadata.newBuilder()
                                .setName(COLUMN_NAME)
                                .setType(COLUMN_TYPE)
                                .setPrimaryKey(true)
                                .setSensitive(false)
                                .build())
                        .addColumns(ColumnMetadata.newBuilder()
                                .setName(COLUMN_NAME_EMAIL)
                                .setType(COLUMN_TYPE_EMAIL)
                                .setPrimaryKey(false)
                                .setSensitive(true)
                                .build())
                        .build())
                .build();

        var source = GetCatalogResponse.newBuilder()
                .setCatalog(Catalog.newBuilder()
                        .addServices(serviceMetadata)
                        .build())
                .build();

        // when
        var result = mapper.toRestGetCatalogResponse(source);

        // then
        Assertions.assertNotNull(result);
        Assertions.assertNotNull(result.getServices());
        Assertions.assertEquals(1, result.getServices().size());

        var service = result.getServices().getFirst();
        Assertions.assertEquals(SERVICE_KEY, service.getName());
        Assertions.assertEquals(SERVICE_ALIAS, service.getDatabaseAlias());

        Assertions.assertNotNull(service.getTables());
        Assertions.assertEquals(1, service.getTables().size());

        var table = service.getTables().getFirst();
        Assertions.assertEquals(TABLE_NAME, table.getName());
        Assertions.assertNotNull(table.getColumns());
        Assertions.assertEquals(2, table.getColumns().size());
        Assertions.assertEquals(COLUMN_NAME, table.getPrimaryKey());

        // Проверяем первую колонку
        var column1 = table.getColumns().getFirst();
        Assertions.assertEquals(COLUMN_NAME, column1.getName());
        Assertions.assertEquals(COLUMN_TYPE, column1.getType());
        Assertions.assertFalse(column1.getSensitive());

        // Проверяем вторую колонку
        var column2 = table.getColumns().get(1);
        Assertions.assertEquals(COLUMN_NAME_EMAIL, column2.getName());
        Assertions.assertEquals(COLUMN_TYPE_EMAIL, column2.getType());
        Assertions.assertTrue(column2.getSensitive());
    }

    @Test
    @DisplayName("Должен вернуть пустой MetadataResponse если catalog отсутствует")
    void toRestGetCatalogResponse_shouldReturnEmptyResponse_whenCatalogMissing() {
        // given
        var source = GetCatalogResponse.newBuilder().build();

        // when
        var result = mapper.toRestGetCatalogResponse(source);

        // then
        Assertions.assertNotNull(result.getServices());
        Assertions.assertTrue(result.getServices().isEmpty());
    }

    @Test
    @DisplayName("Должен вернуть пустой MetadataResponse если response null")
    void toRestGetCatalogResponse_shouldReturnEmptyResponse_whenResponseIsNull() {
        // when
        var result = mapper.toRestGetCatalogResponse(null);

        // then
        Assertions.assertNotNull(result.getServices());
        Assertions.assertTrue(result.getServices().isEmpty());
    }

    @Test
    @DisplayName("Должен корректно маппить таблицу без primary key")
    void toRestGetCatalogResponse_shouldMapsTableWithoutPrimaryKey() {
        // given
        var serviceMetadata = ServiceMetadata.newBuilder()
                .setServiceKey(SERVICE_KEY)
                .setAlias(SERVICE_ALIAS)
                .addTables(TableMetadata.newBuilder()
                        .setName(TABLE_NAME)
                        .addColumns(ColumnMetadata.newBuilder()
                                .setName(COLUMN_NAME)
                                .setType(COLUMN_TYPE)
                                .setPrimaryKey(false)
                                .setSensitive(false)
                                .build())
                        .build())
                .build();

        var source = GetCatalogResponse.newBuilder()
                .setCatalog(Catalog.newBuilder()
                        .addServices(serviceMetadata)
                        .build())
                .build();

        // when
        var result = mapper.toRestGetCatalogResponse(source);

        // then
        Assertions.assertNotNull(result);
        Assertions.assertNotNull(result.getServices());
        var table = result.getServices().getFirst().getTables().getFirst();
        Assertions.assertNull(table.getPrimaryKey());
    }

    // ==================== ТЕСТЫ LIST TABLE ROWS ====================

    @Test
    @DisplayName("Должен корректно преобразовать ListTableRowsResponse в ReadOnlyTableRowsResponseDto")
    void toRestListTableRowsResponse_shouldCorrectMapsAllFields() {
        // given
        var source = ListTableRowsResponse.newBuilder()
                .addRows(Row.newBuilder()
                        .putFields("id", Value.newBuilder()
                                .setStringValue("1")
                                .build())
                        .putFields("name", Value.newBuilder()
                                .setStringValue("Test")
                                .build())
                        .putFields("age", Value.newBuilder()
                                .setIntValue(25)
                                .build())
                        .build())
                .addRows(Row.newBuilder()
                        .putFields("id", Value.newBuilder()
                                .setStringValue("2")
                                .build())
                        .putFields("name", Value.newBuilder()
                                .setStringValue("Test2")
                                .build())
                        .putFields("age", Value.newBuilder()
                                .setIntValue(30)
                                .build())
                        .build())
                .setPagination(PaginationInfo.newBuilder()
                        .setCurrentPage(1)
                        .setPageSize(20)
                        .setTotalRows(2)
                        .setTotalPages(1)
                        .setHasNext(false)
                        .setHasPrev(false)
                        .build())
                .setMeta(MetaInfo.newBuilder()
                        .setServiceKey(SERVICE_KEY)
                        .setTableName(TABLE_NAME)
                        .addAllColumns(List.of("id", "name", "age"))
                        .addAllSortableColumns(List.of("id", "name"))
                        .addAllFilterableColumns(List.of("name", "age"))
                        .build())
                .build();

        // when
        var result = mapper.toRestListTableRowsResponse(source);

        // then
        Assertions.assertNotNull(result);

        // Проверяем данные
        Assertions.assertNotNull(result.getData());
        Assertions.assertEquals(2, result.getData().size());

        var firstRow = result.getData().getFirst();
        Assertions.assertEquals("1", firstRow.get("id"));
        Assertions.assertEquals("Test", firstRow.get("name"));
        Assertions.assertEquals(25L, firstRow.get("age"));

        var secondRow = result.getData().get(1);
        Assertions.assertEquals("2", secondRow.get("id"));
        Assertions.assertEquals("Test2", secondRow.get("name"));
        Assertions.assertEquals(30L, secondRow.get("age"));

        // Проверяем пагинацию
        var pagination = result.getPagination();
        Assertions.assertNotNull(pagination);
        Assertions.assertEquals(1, pagination.getCurrentPage());
        Assertions.assertEquals(20, pagination.getPageSize());
        Assertions.assertEquals(2, pagination.getTotalRows());
        Assertions.assertEquals(1, pagination.getTotalPages());
        Assertions.assertFalse(pagination.getHasNext());
        Assertions.assertFalse(pagination.getHasPrev());

        // Проверяем метаданные
        var meta = result.getMeta();
        Assertions.assertNotNull(meta);
        Assertions.assertEquals(SERVICE_KEY, meta.getService());
        Assertions.assertEquals(TABLE_NAME, meta.getTable());
        Assertions.assertEquals(List.of("id", "name", "age"), meta.getColumns());
        Assertions.assertEquals(List.of("id", "name"), meta.getSortableColumns());
        Assertions.assertEquals(List.of("name", "age"), meta.getFilterableColumns());
    }

    @Test
    @DisplayName("Должен корректно преобразовывать все типы значений Value")
    void rowToMap_shouldCorrectConvertAllValueTypes() {
        // given
        var source = ListTableRowsResponse.newBuilder()
                .addRows(Row.newBuilder()
                        .putFields("string", Value.newBuilder()
                                .setStringValue("test")
                                .build())
                        .putFields("int", Value.newBuilder()
                                .setIntValue(123)
                                .build())
                        .putFields("double", Value.newBuilder()
                                .setDoubleValue(123.45)
                                .build())
                        .putFields("bool", Value.newBuilder()
                                .setBoolValue(true)
                                .build())
                        .putFields("timestamp", Value.newBuilder()
                                .setTimestampValue(1_000_000_000L)
                                .build())
                        .putFields("null", Value.newBuilder()
                                .setNullValue(true)
                                .build())
                        .build())
                .setPagination(PaginationInfo.newBuilder()
                        .setCurrentPage(1)
                        .setPageSize(20)
                        .setTotalRows(1)
                        .setTotalPages(1)
                        .setHasNext(false)
                        .setHasPrev(false)
                        .build())
                .setMeta(MetaInfo.newBuilder()
                        .setServiceKey(SERVICE_KEY)
                        .setTableName(TABLE_NAME)
                        .build())
                .build();

        // when
        var result = mapper.toRestListTableRowsResponse(source);

        // then
        Assertions.assertNotNull(result);
        Assertions.assertEquals(1, result.getData().size());

        var row = result.getData().getFirst();
        Assertions.assertEquals("test", row.get("string"));
        Assertions.assertEquals(123L, row.get("int"));
        Assertions.assertEquals(123.45, row.get("double"));
        Assertions.assertTrue((boolean) row.get("bool"));
        Assertions.assertEquals(Instant.ofEpochSecond(1_000_000_000L), row.get("timestamp"));
        Assertions.assertNull(row.get("null"));
    }

    @Test
    @DisplayName("Должен обрабатывать пустой список строк")
    void toRestListTableRowsResponse_shouldHandleEmptyRows() {
        // given
        var source = ListTableRowsResponse.newBuilder()
                .setPagination(PaginationInfo.newBuilder()
                        .setCurrentPage(1)
                        .setPageSize(20)
                        .setTotalRows(0)
                        .setTotalPages(0)
                        .setHasNext(false)
                        .setHasPrev(false)
                        .build())
                .setMeta(MetaInfo.newBuilder()
                        .setServiceKey(SERVICE_KEY)
                        .setTableName(TABLE_NAME)
                        .build())
                .build();

        // when
        var result = mapper.toRestListTableRowsResponse(source);

        // then
        Assertions.assertNotNull(result);
        Assertions.assertNotNull(result.getData());
        Assertions.assertTrue(result.getData().isEmpty());
        Assertions.assertEquals(0, result.getPagination().getTotalRows());
    }

    @Test
    @DisplayName("Должен обрабатывать строку с null значениями")
    void toRestListTableRowsResponse_shouldHandleNullValues() {
        // given
        var source = ListTableRowsResponse.newBuilder()
                .addRows(Row.newBuilder()
                        .putFields("id", Value.newBuilder()
                                .setStringValue("1")
                                .build())
                        .putFields("nullable", Value.newBuilder()
                                .setNullValue(true)
                                .build())
                        .build())
                .setPagination(PaginationInfo.newBuilder()
                        .setCurrentPage(1)
                        .setPageSize(20)
                        .setTotalRows(1)
                        .setTotalPages(1)
                        .setHasNext(false)
                        .setHasPrev(false)
                        .build())
                .setMeta(MetaInfo.newBuilder()
                        .setServiceKey(SERVICE_KEY)
                        .setTableName(TABLE_NAME)
                        .build())
                .build();

        // when
        var result = mapper.toRestListTableRowsResponse(source);

        // then
        Assertions.assertNotNull(result);
        Assertions.assertEquals(1, result.getData().size());
        Assertions.assertEquals("1", result.getData().getFirst().get("id"));
        Assertions.assertNull(result.getData().getFirst().get("nullable"));
    }

    @Test
    @DisplayName("Должен корректно преобразовывать Timestamp в Instant")
    void convertValue_shouldCorrectConvertTimestampToInstant() {
        // given
        var source = ListTableRowsResponse.newBuilder()
                .addRows(Row.newBuilder()
                        .putFields("created_at", Value.newBuilder()
                                .setTimestampValue(1_700_000_000L)
                                .build())
                        .build())
                .setPagination(PaginationInfo.newBuilder()
                        .setCurrentPage(1)
                        .setPageSize(20)
                        .setTotalRows(1)
                        .setTotalPages(1)
                        .setHasNext(false)
                        .setHasPrev(false)
                        .build())
                .setMeta(MetaInfo.newBuilder()
                        .setServiceKey(SERVICE_KEY)
                        .setTableName(TABLE_NAME)
                        .build())
                .build();

        // when
        var result = mapper.toRestListTableRowsResponse(source);

        // then
        Assertions.assertNotNull(result);
        var createdAt = result.getData().getFirst().get("created_at");
        Assertions.assertInstanceOf(Instant.class, createdAt);
        Assertions.assertEquals(Instant.ofEpochSecond(1_700_000_000L), createdAt);
    }

    // ==================== ТЕСТЫ ДЛЯ ПРОВЕРКИ МАППИНГА СТРУКТУР ====================

    @ParameterizedTest
    @MethodSource("paginationArguments")
    @DisplayName("Должен корректно маппить все варианты пагинации")
    void toRestListTableRowsResponse_shouldCorrectMapPagination(
            int currentPage,
            int pageSize,
            long totalRows,
            int totalPages,
            boolean hasNext,
            boolean hasPrev
    ) {
        // given
        var source = ListTableRowsResponse.newBuilder()
                .setPagination(PaginationInfo.newBuilder()
                        .setCurrentPage(currentPage)
                        .setPageSize(pageSize)
                        .setTotalRows(totalRows)
                        .setTotalPages(totalPages)
                        .setHasNext(hasNext)
                        .setHasPrev(hasPrev)
                        .build())
                .setMeta(MetaInfo.newBuilder()
                        .setServiceKey(SERVICE_KEY)
                        .setTableName(TABLE_NAME)
                        .build())
                .build();

        // when
        var result = mapper.toRestListTableRowsResponse(source);

        // then
        var pagination = result.getPagination();
        Assertions.assertEquals(currentPage, pagination.getCurrentPage());
        Assertions.assertEquals(pageSize, pagination.getPageSize());
        Assertions.assertEquals(totalRows, pagination.getTotalRows());
        Assertions.assertEquals(totalPages, pagination.getTotalPages());
        Assertions.assertEquals(hasNext, pagination.getHasNext());
        Assertions.assertEquals(hasPrev, pagination.getHasPrev());
    }

    private static Stream<Arguments> paginationArguments() {
        return Stream.of(
                Arguments.of(1, 20, 100L, 5, true, false),  // Первая страница
                Arguments.of(3, 20, 100L, 5, true, true),   // Средняя страница
                Arguments.of(5, 20, 100L, 5, false, true),  // Последняя страница
                Arguments.of(1, 20, 0L, 0, false, false),   // Пустой результат
                Arguments.of(1, 100, 50L, 1, false, false)  // Одна страница
        );
    }
}
