package ru.taska.transport.grpc;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import ru.taska.api.workflow.v1.GetWorkflowForProjectRequest;
import ru.taska.api.workflow.v1.ReactorWorkflowServiceGrpc;
import ru.taska.config.props.GrpcClientProperties;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.dto.IssueTypeDto;
import ru.taska.domain.dto.WorkflowResponseDto;
import ru.taska.mapper.WorkflowMapper;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Реактивный gRPC-клиент для взаимодействия с микросервисом управления бизнес-процессами (WorkflowService).
 * <p>
 * Класс инкапсулирует вызовы удаленных процедур через {@link ReactorWorkflowServiceGrpc.ReactorWorkflowServiceStub},
 * управляет динамическими таймаутами (Deadlines) для каждого запроса и координирует маппинг
 * данных между REST DTO и gRPC Protobuf моделями.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GrpcWorkflowServiceClient {

    private final ReactorWorkflowServiceGrpc.ReactorWorkflowServiceStub workflowServiceStub;
    private final WorkflowMapper workflowMapper;
    private final GrpcClientProperties properties;

    /**
     * Создает копию gRPC-стаба с динамически настроенным временем ожидания (Deadline).
     * <p>
     * Значение таймаута извлекается из конфигурационных свойств приложения для WorkflowService.
     *
     * @return {@link ReactorWorkflowServiceGrpc.ReactorWorkflowServiceStub} с примененным таймаутом выполнения
     */
    private ReactorWorkflowServiceGrpc.ReactorWorkflowServiceStub dynamicStub() {
        return workflowServiceStub.withDeadlineAfter(
                properties.workflowService().deadlineDuration().toMillis(),
                TimeUnit.MILLISECONDS
        );
    }

    /**
     * Запрашивает схему workflow, привязанную к указанному проекту и типу задачи.
     * <p>
     * Метод формирует сетевой gRPC-запрос, внедряет сквозной контекст авторизации пользователя (actor_user_id)
     * и преобразует полученный результат во внутреннюю модель ответа API Gateway.
     *
     * @param projectId    идентификатор целевого проекта (UUID)
     * @param issueTypeDto тип задачи из REST API контракта (TASK, BUG, STORY)
     * @param context      текущий контекст окружения и сквозной трассировки запроса на Gateway
     * @return {@link Mono} с заполненным REST DTO {@link WorkflowResponseDto}, содержащим статусы и переходы
     */
    public Mono<WorkflowResponseDto> getWorkflowForProject(
            UUID projectId,
            IssueTypeDto issueTypeDto,
            GatewayContext context
    ) {
        log.info("[{}] Calling getWorkflowForProject for project: {}",context.requestId(), projectId);

        GetWorkflowForProjectRequest grpcRequest = workflowMapper
                .toGetWorkflowGrpcRequest(projectId, issueTypeDto, context);

        return dynamicStub().getWorkflowForProject(grpcRequest)
                .map(workflowMapper::toWorkflowResponseRestDto);
    }
}
