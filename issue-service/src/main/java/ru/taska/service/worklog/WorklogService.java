package ru.taska.service.worklog;

import reactor.core.publisher.Mono;
import ru.taska.domain.Worklog;
import ru.taska.domain.dto.CreateWorklogDto;
import ru.taska.domain.dto.UpdateWorklogDto;

import java.util.List;
import java.util.UUID;

public interface WorklogService {

    Mono<Worklog> addIssueWorklog(
            String requestId,
            String nodeId,
            UUID projectId,
            UUID issueId,
            UUID actorUserId,
            CreateWorklogDto createWorklogDto
            );

    Mono<Worklog> updateIssueWorklog(
            String requestId,
            String nodeId,
            UUID projectId,
            UUID issueId,
            UUID worklog,
            UUID actorUserId,
            UpdateWorklogDto updateWorklogDto
    );

    Mono<List<Worklog>> listIssueWorklog(
            String requestId,
            String nodeId,
            UUID projectId,
            UUID issueId,
            UUID actorUserId
    );

    Mono<Worklog> deleteIssueWorklog(
            String requestId,
            String nodeId,
            UUID projectId,
            UUID issueId,
            UUID worklog,
            UUID actorUserId
    );
}
