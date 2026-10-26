package ru.taska.repository;

import java.util.UUID;
import reactor.core.publisher.Mono;
import ru.taska.domain.aggregate.WorklogRows;

public interface WorklogRepositoryCustom {
  Mono<WorklogRows> findActivePageWithCount(UUID issueId, int pageSize, long offset);
}
