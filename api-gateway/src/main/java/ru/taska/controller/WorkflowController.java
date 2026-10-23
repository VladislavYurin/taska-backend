package ru.taska.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import ru.taska.api.WorkflowApi;
import ru.taska.domain.EndpointSecurity;
import ru.taska.domain.dto.CreateWorkflowRequestDto;
import ru.taska.domain.dto.CreateWorkflowStatusRequestDto;
import ru.taska.domain.dto.CreateWorkflowTransitionRequestDto;
import ru.taska.domain.dto.IssueTypeDto;
import ru.taska.domain.dto.ProjectWorkflowsResponseDto;
import ru.taska.domain.dto.WorkflowResponseDto;
import ru.taska.filter.GatewayRequestExecutor;
import ru.taska.transport.grpc.GrpcWorkflowServiceClient;
import ru.taska.transport.grpc.GrpcProjectServiceClient;

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
    private final GrpcProjectServiceClient projectClient;

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
                projectClient.getProject(projectId.toString(), context)
                        .then(Mono.defer(() -> grpcWorkflowServiceClient.getWorkflowForProject(projectId, issueType, context)))
                        .map(ResponseEntity::ok));
    }

    /**
     * Возвращает все workflow проекта со всеми статусами и переходами, включая скрытые.
     * <p>
     * Как и чтение одного workflow, сначала проверяет, что пользователь видит проект.
     *
     * @param projectId идентификатор проекта
     * @param exchange  текущий серверный обмен (HTTP-запрос/ответ)
     * @return {@link Mono} с {@link ResponseEntity} 200 (OK) и {@link ProjectWorkflowsResponseDto}
     */
    @Override
    public Mono<ResponseEntity<ProjectWorkflowsResponseDto>> listProjectWorkflows(
            UUID projectId,
            ServerWebExchange exchange
    ) {
        return executor.execute(exchange, EndpointSecurity.PROTECTED, context ->
                projectClient.getProject(projectId.toString(), context)
                        .then(Mono.defer(() -> grpcWorkflowServiceClient.listProjectWorkflows(projectId, context)))
                        .map(ResponseEntity::ok));
    }

    /**
     * Создаёт workflow проекта. Роль пользователя в проекте проверяет workflow-service.
     *
     * @param projectId идентификатор проекта
     * @param request   тело запроса со статусами, переходами и типами задач
     * @param exchange  текущий серверный обмен (HTTP-запрос/ответ)
     * @return {@link Mono} с {@link ResponseEntity} 201 (CREATED) и созданным workflow
     */
    @Override
    public Mono<ResponseEntity<WorkflowResponseDto>> createProjectWorkflow(
            UUID projectId,
            Mono<CreateWorkflowRequestDto> request,
            ServerWebExchange exchange
    ) {
        return executor.execute(exchange, EndpointSecurity.PROTECTED, context ->
                grpcWorkflowServiceClient.createWorkflow(projectId, request, context)
                        .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(response)));
    }

    /**
     * Добавляет статус в workflow проекта. Роль пользователя в проекте проверяет workflow-service.
     *
     * @param projectId  идентификатор проекта
     * @param workflowId идентификатор workflow проекта
     * @param request    добавляемый статус
     * @param exchange   текущий серверный обмен (HTTP-запрос/ответ)
     * @return {@link Mono} с {@link ResponseEntity} 201 (CREATED) и workflow целиком
     */
    @Override
    public Mono<ResponseEntity<WorkflowResponseDto>> addWorkflowStatus(
            UUID projectId,
            UUID workflowId,
            Mono<CreateWorkflowStatusRequestDto> request,
            ServerWebExchange exchange
    ) {
        return executor.execute(exchange, EndpointSecurity.PROTECTED, context ->
                grpcWorkflowServiceClient.addWorkflowStatus(projectId, workflowId, request, context)
                        .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(response)));
    }

    /**
     * Добавляет переход в workflow проекта. Роль пользователя в проекте проверяет workflow-service.
     *
     * @param projectId  идентификатор проекта
     * @param workflowId идентификатор workflow проекта
     * @param request    добавляемый переход
     * @param exchange   текущий серверный обмен (HTTP-запрос/ответ)
     * @return {@link Mono} с {@link ResponseEntity} 201 (CREATED) и workflow целиком
     */
    @Override
    public Mono<ResponseEntity<WorkflowResponseDto>> addWorkflowTransition(
            UUID projectId,
            UUID workflowId,
            Mono<CreateWorkflowTransitionRequestDto> request,
            ServerWebExchange exchange
    ) {
        return executor.execute(exchange, EndpointSecurity.PROTECTED, context ->
                grpcWorkflowServiceClient.addWorkflowTransition(projectId, workflowId, request, context)
                        .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(response)));
    }
}
