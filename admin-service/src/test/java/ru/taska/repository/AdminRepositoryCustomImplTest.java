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
import org.springframework.data.r2dbc.convert.MappingR2dbcConverter;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.r2dbc.dialect.PostgresDialect;
import org.springframework.data.r2dbc.mapping.R2dbcMappingContext;
import org.springframework.data.r2dbc.query.BoundCondition;
import org.springframework.data.r2dbc.query.QueryMapper;
import org.springframework.data.relational.core.query.Query;
import org.springframework.data.relational.core.sql.Table;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.dto.FilterAuditDTO;
import ru.taska.entity.AuditLog;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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

    private final QueryMapper queryMapper = new QueryMapper(
            PostgresDialect.INSTANCE,
            new MappingR2dbcConverter(new R2dbcMappingContext())
    );

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
        Assertions.assertThat(query.getSort().getOrderFor("id"))
                .as("tie-breaker для одинакового created_at")
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

        BoundCondition condition = mapCriteria(capturedSelectQuery());

        Assertions.assertThat(toSqlWithoutParentheses(condition)).isEqualTo(
                "audit.actor_user_id = ?[$1]"
                        + " AND audit.action = ?[$2]"
                        + " AND audit.target_service = ?[$3]"
                        + " AND audit.target_table = ?[$4]"
                        + " AND audit.target_id = ?[$5]"
                        + " AND audit.request_id = ?[$6]"
                        + " AND audit.created_at >= ?[$7]"
                        + " AND audit.created_at <= ?[$8]");
        Assertions.assertThat(boundValues(condition)).containsExactly(
                actor,
                "USER_BLOCKED",
                "auth-service",
                "users",
                "target-1",
                "req-1",
                Instant.parse("2024-01-01T00:00:00Z"),
                Instant.parse("2024-01-31T23:59:59Z"));
    }

    @Test
    void findByFilter_withOnlyCreatedAtFrom_addsLowerBoundOnly() {
        Instant from = Instant.parse("2024-01-01T00:00:00Z");

        repository.findByFilter(FilterAuditDTO.builder().createdAtFrom(from).build(), 10, 0L).blockLast();

        BoundCondition condition = mapCriteria(capturedSelectQuery());
        Assertions.assertThat(toSqlWithoutParentheses(condition)).isEqualTo("audit.created_at >= ?[$1]");
        Assertions.assertThat(boundValues(condition)).containsExactly(from);
    }

    @Test
    void findByFilter_withOnlyCreatedAtTo_addsUpperBoundOnly() {
        Instant to = Instant.parse("2024-01-31T23:59:59Z");

        repository.findByFilter(FilterAuditDTO.builder().createdAtTo(to).build(), 10, 0L).blockLast();

        BoundCondition condition = mapCriteria(capturedSelectQuery());
        Assertions.assertThat(toSqlWithoutParentheses(condition)).isEqualTo("audit.created_at <= ?[$1]");
        Assertions.assertThat(boundValues(condition)).containsExactly(to);
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
    void findByFilter_ignoresWhitespaceOnlyStringFilters() {
        FilterAuditDTO filter = new FilterAuditDTO(null, " ", "  ", "\t", " \n", "   ", null, null);

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

        BoundCondition condition = mapCriteria(query);
        Assertions.assertThat(toSqlWithoutParentheses(condition)).isEqualTo("audit.request_id = ?[$1]");
        Assertions.assertThat(boundValues(condition)).containsExactly("req-1");
    }

    private Query capturedSelectQuery() {
        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        Mockito.verify(template).select(captor.capture(), Mockito.eq(AuditLog.class));
        return captor.getValue();
    }

    /** Переводит Criteria запроса в SQL-условие так же, как это делает R2DBC при выполнении запроса. */
    private BoundCondition mapCriteria(Query query) {
        Assertions.assertThat(query.getCriteria()).isPresent();
        return queryMapper.getMappedObject(
                PostgresDialect.INSTANCE.getBindMarkersFactory().create(),
                query.getCriteria().get(),
                Table.create("audit"),
                null
        );
    }

    /**
     * Скобки зависят только от того, как собран Criteria (цепочка {@code and()} или список условий),
     * и при одних AND на смысл не влияют. Без них тест проверяет сами условия, а не форму Criteria.
     */
    private String toSqlWithoutParentheses(BoundCondition condition) {
        return condition.getCondition().toString().replace("(", "").replace(")", "");
    }

    private List<Object> boundValues(BoundCondition condition) {
        List<Object> values = new ArrayList<>();
        condition.getBindings().forEach(binding -> values.add(binding.getValue()));
        return values;
    }
}
