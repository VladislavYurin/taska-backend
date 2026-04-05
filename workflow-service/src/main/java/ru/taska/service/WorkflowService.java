package ru.taska.service;

import reactor.core.publisher.Mono;
import ru.taska.domain.WorkflowAggregate;

import java.util.UUID;

public interface WorkflowService {

    Mono<WorkflowAggregate> getWorkflow(UUID projectId, String issueType);
}