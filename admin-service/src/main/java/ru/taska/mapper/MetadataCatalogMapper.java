package ru.taska.mapper;

import org.springframework.stereotype.Component;
import ru.taska.api.admin.v1.Catalog;
import ru.taska.api.admin.v1.ColumnMetadata;
import ru.taska.api.admin.v1.GetCatalogResponse;
import ru.taska.api.admin.v1.ServiceMetadata;
import ru.taska.api.admin.v1.TableMetadata;
import ru.taska.dto.CatalogDto;
import ru.taska.dto.ColumnDto;
import ru.taska.dto.ServiceDto;
import ru.taska.dto.TableDto;

/**
 * Маппит внутренние DTO каталога в protobuf-ответ gRPC API.
 */
@Component
public class MetadataCatalogMapper {

    /**
     * Оборачивает каталог в gRPC-ответ метода {@code GetCatalog}.
     */
    public GetCatalogResponse toGetCatalogResponse(CatalogDto catalog) {
        return GetCatalogResponse.newBuilder()
                .setCatalog(toCatalog(catalog))
                .build();
    }

    private Catalog toCatalog(CatalogDto catalog) {
        Catalog.Builder builder = Catalog.newBuilder();
        for (ServiceDto service : catalog.services()) {
            builder.addServices(toServiceMetadata(service));
        }
        return builder.build();
    }

    private ServiceMetadata toServiceMetadata(ServiceDto service) {
        ServiceMetadata.Builder builder = ServiceMetadata.newBuilder()
                .setServiceKey(service.serviceKey())
                .setAlias(service.alias());
        for (TableDto table : service.tables()) {
            builder.addTables(toTableMetadata(table));
        }
        return builder.build();
    }

    private TableMetadata toTableMetadata(TableDto table) {
        TableMetadata.Builder builder = TableMetadata.newBuilder()
                .setName(table.name());
        for (ColumnDto column : table.columns()) {
            builder.addColumns(toColumnMetadata(column));
        }
        return builder.build();
    }

    private ColumnMetadata toColumnMetadata(ColumnDto column) {
        return ColumnMetadata.newBuilder()
                .setName(column.name())
                .setType(column.type())
                .setPrimaryKey(column.primaryKey())
                .setSensitive(column.sensitive())
                .build();
    }
}
