package ru.taska.service;

import reactor.core.publisher.Mono;
import ru.taska.domain.aggregate.IssueDetailsAggregate;

import java.util.UUID;

public interface IssueDetailsService {
    Mono<IssueDetailsAggregate> getIssueDetails(
            String requestId,
            String nodeId,
            UUID issueId,
            UUID actorUserId
    );
}
