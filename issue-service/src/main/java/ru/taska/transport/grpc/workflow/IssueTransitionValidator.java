package ru.taska.transport.grpc.workflow;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ru.taska.api.workflow.v1.TransitionViolation;
import ru.taska.api.workflow.v1.ValidateTransitionResponse;
import ru.taska.domain.Issue;
import ru.taska.domain.IssueStatus;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.mapper.IssueMapper;

import java.util.UUID;

/**
 * Компонент, валидирующий переход по workflow.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IssueTransitionValidator {

    private final GrpcWorkflowServiceClient client;
    private final IssueMapper issueMapper;


    /**
     * Передает данные для валидации перехода по workflow gRPC клиенту {@link GrpcWorkflowServiceClient#validateTransition},
     * и получает от него ответ в виде асинхронного контейнера {@link Mono}, содержащим {@link ValidateTransitionResponse}.
     * В случае успешной валидации конвертирует ответ в доменный статус задачи {@link IssueStatus} и возвращает его
     * внутри асинхронного контейнера {@link Mono}.
     * Если валидация не прошла, то выбрасывает исключение {@link DomainException} со статусом ошибки {@link DomainStatus#FAILED_PRECONDITION}
     * и сообщением, которое фомируется из пришедшего из workflow-service списка нарушений перехода.
     */
    public Mono<IssueStatus> validateTransition(
            String requestId,
            String nodeId,
            Issue issue,
            UUID transitionId,
            UUID actorUserId,
            String payload
    ) {
        return client.validateTransition(requestId, nodeId, issue, transitionId, actorUserId, payload)
                .flatMap(response ->
                        processResponse(issue, transitionId, response)
                );
    }

    private Mono<IssueStatus> processResponse(
            Issue issue,
            UUID transitionId,
            ValidateTransitionResponse response
    ) {
        if (!response.getBody().getIsValid()) {
            var violationMessage = response.getBody().getTransitionViolationsList().stream()
                    .map(TransitionViolation::getMessage)
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("Issue transition not allowed");

            log.warn("[{}][{}] Issue transition not allowed: issueId={}, projectId={}, transitionId={}, violations={}",
                    response.getHeader().getRequestId(),
                    response.getHeader().getNodeId(),
                    issue.getId(),
                    issue.getProjectId(),
                    transitionId,
                    violationMessage
            );

            return Mono.error(new DomainException(DomainStatus.FAILED_PRECONDITION, violationMessage));
        }

        return Mono.just(issueMapper.toDomainIssueStatus(response.getBody().getToStatusKey()));
    }
}
