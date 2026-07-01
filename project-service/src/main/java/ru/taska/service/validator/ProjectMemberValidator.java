package ru.taska.service.validator;

import reactor.core.publisher.Mono;

import java.util.UUID;

public interface ProjectMemberValidator {

    Mono<Boolean> validateBeforeAdd(String requestId, String nodeId, UUID actorUserId, UUID addedMemberId, UUID projectId);
    Mono<Boolean> validateBeforeModify(String requestId, String nodeId, UUID actorUserId, UUID changedMemberId, UUID projectId);
}
