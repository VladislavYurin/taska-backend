package ru.taska.service.impl.validator;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.repository.ProjectRepository;
import ru.taska.service.validator.ProjectValidator;

@Component
@Slf4j
@RequiredArgsConstructor
public class ProjectValidatorImpl implements ProjectValidator {
    private final ProjectRepository projectRepository;

    @Override
    public Mono<Void> isArchived(String requestId, String nodeId, UUID projectId) {
        return projectRepository.findById(projectId)
                .flatMap(project -> {
                    if (project.getArchivedAt() != null) {
                        log.info("[{}][{}] Project {} has been archived",
                                 requestId, nodeId, projectId);
                        return Mono.error(new DomainException(
                                DomainStatus.PROJECT_ARCHIVED, "Project " + projectId + " has been archived"));
                    };
                    return Mono.empty();
                });
    }
}
