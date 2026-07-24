package ru.taska.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ru.taska.api.WorkflowApi;
import ru.taska.domain.EndpointSecurity;
import ru.taska.domain.dto.IssueTypeDto;
import ru.taska.domain.dto.WorkflowResponseDto;
import ru.taska.filter.GatewayRequestExecutor;
import ru.taska.transport.grpc.GrpcWorkflowServiceClient;

import java.util.UUID;

/**
 * REST-контроллер API Gateway для получения информации о workflow проектов.
 * <p>
 * Обеспечивает проксирование входящих REST-запросов во внутренний сервис Workflow через gRPC,
 * оборачивая вызовы в логику построения сквозного контекста выполнения (трассировка, метаданные узла).
 * Реализует сгенерированный интерфейс {@link WorkflowApi}.
 */
@RestController
@RequiredArgsConstructor
public class WorkflowController implements WorkflowApi {

    private final GatewayRequestExecutor executor;
    private final GrpcWorkflowServiceClient grpcWorkflowServiceClient;

    /**
     * Возвращает workflow проекта для указанного типа задачи.
     * <p>
     * Эндпоинт является защищённым (PROTECTED). Перед выполнением метода фабрика контекстов автоматически
     * извлекает заголовок Authorization, проверяет Bearer-токен через gRPC и маппит данные пользователя,
     * очищая системные прото-префиксы у статусов.
     *
     * @param projectId идентификатор проекта
     * @param issueType тип задачи, для которого необходимо получить workflow
     * @param exchange текущий серверный обмен (HTTP-запрос/ответ)
     * @return {@link Mono}, содержащий {@link ResponseEntity} со статусом 200 (OK)
     * и данными workflow внутри {@link WorkflowResponseDto}
     */
    public Mono<ResponseEntity<WorkflowResponseDto>> getWorkflowForProject(
            UUID projectId,
            IssueTypeDto issueType,
            ServerWebExchange exchange)
    {

        return executor.execute(exchange, EndpointSecurity.PROTECTED, context ->
                grpcWorkflowServiceClient.getWorkflowForProject(projectId, issueType, context)
                        .map(ResponseEntity::ok));
    }

}
