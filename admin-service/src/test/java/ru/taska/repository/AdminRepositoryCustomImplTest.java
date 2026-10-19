package ru.taska.repository;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Query;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.dto.FilterAuditDTO;
import ru.taska.entity.AuditLog;

import java.time.Instant;
import java.util.UUID;

/**
 * Проверяет сборку {@link Query} для журнала аудита: {@link Query} иммутабелен,
 * поэтому limit/offset/sort должны реально попасть в запрос, а не потеряться.
 */
@ExtendWith(MockitoExtension.class)
class AdminRepositoryCustomImplTest {

    @Mock
    private R2dbcEntityTemplate template;

    private AdminRepositoryCustomImpl repository;

    @BeforeEach
    void setUp() {
        repository = new AdminRepositoryCustomImpl(template);
        Mockito.lenient().when(template.select(Mockito.any(Query.class), Mockito.eq(AuditLog.class)))
                .thenReturn(Flux.empty());
        Mockito.lenient().when(template.count(Mockito.any(Query.class), Mockito.eq(AuditLog.class)))
                .thenReturn(Mono.just(0L));
    }

    @Test
    void findByFilter_appliesLimitOffsetAndCreatedAtDescSort() {
        repository.findByFilter(FilterAuditDTO.builder().build(), 25, 50L).blockLast();

        Query query = capturedSelectQuery();

        Assertions.assertThat(query.isLimited()).isTrue();
        Assertions.assertThat(query.getLimit()).isEqualTo(25);
        Assertions.assertThat(query.getOffset()).isEqualTo(50L);
        Assertions.assertThat(query.isSorted()).isTrue();
        Assertions.assertThat(query.getSort().getOrderFor("created_at"))
                .isNotNull()
                .extracting(Sort.Order::getDirection)
                .isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void findByFilter_withoutFilters_buildsEmptyCriteria() {
        repository.findByFilter(null, 10, 0L).blockLast();

        Query query = capturedSelectQuery();

        Assertions.assertThat(query.getCriteria()).isPresent();
        Assertions.assertThat(query.getCriteria().get().isEmpty()).isTrue();
    }

    @Test
    void findByFilter_withFilters_addsEveryConditionToCriteria() {
        UUID actor = UUID.randomUUID();
        FilterAuditDTO filter = new FilterAuditDTO(
                actor,
                "USER_BLOCKED",
                "auth-service",
                "users",
                "target-1",
                "req-1",
                Instant.parse("2024-01-01T00:00:00Z"),
                Instant.parse("2024-01-31T23:59:59Z")
        );

        repository.findByFilter(filter, 10, 0L).blockLast();

        Query query = capturedSelectQuery();
        Assertions.assertThat(query.getCriteria()).isPresent();
        String criteria = query.getCriteria().get().toString();

        Assertions.assertThat(criteria)
                .contains("actor_user_id")
                .contains("action")
                .contains("target_service")
                .contains("target_table")
                .contains("target_id")
                .contains("request_id")
                .contains("created_at");
    }

    @Test
    void findByFilter_ignoresBlankStringFilters() {
        FilterAuditDTO filter = new FilterAuditDTO(null, "", "", "", "", "", null, null);

        repository.findByFilter(filter, 10, 0L).blockLast();

        Query query = capturedSelectQuery();
        Assertions.assertThat(query.getCriteria()).isPresent();
        Assertions.assertThat(query.getCriteria().get().isEmpty()).isTrue();
    }

    @Test
    void countByFilter_doesNotApplyPagination() {
        repository.countByFilter(FilterAuditDTO.builder().requestId("req-1").build()).block();

        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        Mockito.verify(template).count(captor.capture(), Mockito.eq(AuditLog.class));

        Query query = captor.getValue();
        Assertions.assertThat(query.isLimited()).isFalse();
        Assertions.assertThat(query.getOffset()).isLessThanOrEqualTo(0L);
        Assertions.assertThat(query.getCriteria()).isPresent();
        Assertions.assertThat(query.getCriteria().get().toString()).contains("request_id");
    }

    private Query capturedSelectQuery() {
        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        Mockito.verify(template).select(captor.capture(), Mockito.eq(AuditLog.class));
        return captor.getValue();
    }
}
