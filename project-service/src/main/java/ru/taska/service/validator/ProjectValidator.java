package ru.taska.service.validator;

import java.util.UUID;
import reactor.core.publisher.Mono;

public interface ProjectValidator {

    Mono<Void> isArchived(String requestId, String nodeId, UUID projectId);

}
