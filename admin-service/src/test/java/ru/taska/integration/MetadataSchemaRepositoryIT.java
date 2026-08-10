package ru.taska.integration;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.test.StepVerifier;
import ru.taska.domain.ColumnMetadata;
import ru.taska.domain.PrimaryKeyMetadata;
import ru.taska.repository.MetadataSchemaRepository;

import java.time.Duration;
import java.util.List;

class MetadataSchemaRepositoryIT extends AbstractIT {

    @Autowired
    private MetadataSchemaRepository metadataSchemaRepository;

    @Test
    void findColumns_returnsFixtureTableColumns() {
        List<ColumnMetadata> columns = metadataSchemaRepository
                .findColumns(FIXTURE_SERVICE, FIXTURE_SCHEMA)
                .filter(column -> FIXTURE_TABLE.equals(column.tableName()))
                .collectList()
                .block(Duration.ofSeconds(10));

        Assertions.assertThat(columns).isNotNull();
        Assertions.assertThat(columns)
                .extracting(ColumnMetadata::columnName)
                .contains("id", "login", "email", "created_at", "age");
        Assertions.assertThat(columns)
                .filteredOn(c -> "login".equals(c.columnName()))
                .extracting(ColumnMetadata::dataType)
                .containsExactly("character varying");
    }

    @Test
    void findPrimaryKeys_returnsFixtureTablePk() {
        StepVerifier.create(
                        metadataSchemaRepository.findPrimaryKeys(FIXTURE_SERVICE, FIXTURE_SCHEMA)
                                .filter(pk -> FIXTURE_TABLE.equals(pk.tableName()))
                )
                .assertNext(pk -> {
                    Assertions.assertThat(pk).isEqualTo(new PrimaryKeyMetadata(FIXTURE_TABLE, "id"));
                })
                .verifyComplete();
    }
}
