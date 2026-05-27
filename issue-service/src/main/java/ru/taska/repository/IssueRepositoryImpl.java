package ru.taska.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate;
import org.springframework.data.relational.core.query.Criteria;
import org.springframework.data.relational.core.query.Query;
import reactor.core.publisher.Flux;
import ru.taska.domain.Issue;
import ru.taska.domain.IssueStatus;

import java.util.UUID;

@RequiredArgsConstructor
public class IssueRepositoryImpl implements IssueRepositoryCustom {

    private final R2dbcEntityTemplate r2dbcEntityTemplate;

    @Override
    public Flux<Issue> findByFilter(UUID projectId, IssueStatus status, UUID assigneeId) {
        return r2dbcEntityTemplate.select(Issue.class)
                .matching(buildListQuery(projectId, status, assigneeId))
                .all();
    }

    private Query buildListQuery(UUID projectId, IssueStatus status, UUID assigneeId) {
        Criteria criteria = Criteria.where("project_id").is(projectId)
                .and("deleted_at").isNull();

        if (status != null) {
            criteria = criteria.and("status_key").is(status.name());
        }
        if (assigneeId != null) {
            criteria = criteria.and("assignee_id").is(assigneeId);
        }

        return Query.query(criteria);
    }
}
