package ru.taska.transport.grpc.validator;

import reactor.core.publisher.Mono;
import ru.taska.api.common.v1.Header;
import ru.taska.transport.grpc.dto.ValidatedIssueRequest;
import validator.GrpcRequestValidators;

/**
 * Валидатор входных данных gRPC-запросов, связанных с задачей.
 *
 * <p>Проверяет обязательные поля запроса и преобразует значения
 * из protobuf-представления в типизированные значения, используемые
 * бизнес-логикой.</p>
 */
public class IssueGrpcRequestValidators {
    private IssueGrpcRequestValidators() {
    }

    /**
     * Валидирует обязательные поля запроса, связанные с задачей.
     *
     * @param header заголовок gRPC-запроса
     * @param issueIdRaw идентификатор задачи в строковом представлении
     * @param actorUserIdRaw идентификатор пользователя в строковом представлении
     * @return валидированный запрос с преобразованными идентификаторами
     */
    public static Mono<ValidatedIssueRequest> validateIssueScopedRequest(
            Header header, String issueIdRaw, String actorUserIdRaw
    ) {
        return Mono.zip(
                GrpcRequestValidators.requireNonBlankOrInvalidArgument(header.getRequestId(), "header.requestId"),
                GrpcRequestValidators.requireNonBlankOrInvalidArgument(header.getNodeId(), "header.nodeId"),
                GrpcRequestValidators.parseUuidOrInvalidArgument(issueIdRaw, "body.issueId"),
                GrpcRequestValidators.parseUuidOrInvalidArgument(actorUserIdRaw, "body.actorUserId")
        ).map(t -> new ValidatedIssueRequest(t.getT1(), t.getT2(), t.getT3(), t.getT4()));
    }
}
