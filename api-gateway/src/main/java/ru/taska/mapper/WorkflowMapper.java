package ru.taska.mapper;

import com.google.protobuf.Timestamp;
import org.springframework.stereotype.Component;
import ru.taska.api.common.v1.Header;
import ru.taska.api.workflow.v1.AddWorkflowStatusRequest;
import ru.taska.api.workflow.v1.AddWorkflowStatusRequestBody;
import ru.taska.api.workflow.v1.AddWorkflowTransitionRequest;
import ru.taska.api.workflow.v1.AddWorkflowTransitionRequestBody;
import ru.taska.api.workflow.v1.CreateWorkflowRequest;
import ru.taska.api.workflow.v1.CreateWorkflowRequestBody;
import ru.taska.api.workflow.v1.GetWorkflowForProjectRequest;
import ru.taska.api.workflow.v1.GetWorkflowForProjectRequestBody;
import ru.taska.api.workflow.v1.IssueType;
import ru.taska.api.workflow.v1.ListProjectWorkflowsRequest;
import ru.taska.api.workflow.v1.ListProjectWorkflowsRequestBody;
import ru.taska.api.workflow.v1.ListProjectWorkflowsResponse;
import ru.taska.api.workflow.v1.ProjectWorkflow;
import ru.taska.api.workflow.v1.StatusCategory;
import ru.taska.api.workflow.v1.WorkflowResponse;
import ru.taska.api.workflow.v1.WorkflowStatus;
import ru.taska.api.workflow.v1.WorkflowStatusCreateRequest;
import ru.taska.api.workflow.v1.WorkflowTransition;
import ru.taska.api.workflow.v1.WorkflowTransitionCreateRequest;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.dto.CreateWorkflowRequestDto;
import ru.taska.domain.dto.CreateWorkflowStatusRequestDto;
import ru.taska.domain.dto.CreateWorkflowTransitionRequestDto;
import ru.taska.domain.dto.IssueTypeDto;
import ru.taska.domain.dto.ProjectWorkflowDto;
import ru.taska.domain.dto.ProjectWorkflowsResponseDto;
import ru.taska.domain.dto.WorkflowResponseDto;
import ru.taska.domain.dto.WorkflowStatusCategoryDto;
import ru.taska.domain.dto.WorkflowStatusDto;
import ru.taska.domain.dto.WorkflowTransitionDto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;


/**
 * Компонент-маппер для преобразования моделей данных слоя управления workflow.
 * <p>
 * Обеспечивает конвертацию объектов REST API (DTO) в объекты
 * сетевого gRPC-транспорта (Protobuf Messages), используемые
 * при взаимодействии с сервисом Workflow, а также обратное преобразование
 * ответов во внутренние форматы передачи данных шлюза.
 */
@Component
public class WorkflowMapper {
    public ProjectWorkflowDto toMutationResponseRestDto(WorkflowResponse response) {
        return toProjectWorkflowDto(ProjectWorkflow.newBuilder().setWorkflow(response)
                .addAllIssueTypes(response.getIssueTypesList()).setIsShared(response.getIsShared()).build());
    }

    private static final String STATUS_CATEGORY_PREFIX = "STATUS_CATEGORY_";

    /**
     * Преобразует параметры REST-запроса и контекст шлюза
     * в gRPC-запрос получения workflow проекта.
     *
     * @param projectId    идентификатор проекта
     * @param issueTypeDto тип задачи, полученный из REST API
     * @param context      текущий контекст сквозной трассировки и идентификации узла шлюза
     * @return сформированный gRPC-объект {@link GetWorkflowForProjectRequest}
     * с заполненным заголовком и телом запроса
     */
    public GetWorkflowForProjectRequest toGetWorkflowGrpcRequest(
            UUID projectId,
            IssueTypeDto issueTypeDto,
            GatewayContext context
    ) {
        return GetWorkflowForProjectRequest.newBuilder()
                .setHeader(Header.newBuilder()
                        .setRequestId(context.requestId())
                        .setNodeId(context.nodeId())
                        .build())
                .setBody(GetWorkflowForProjectRequestBody.newBuilder()
                        .setProjectId(projectId.toString())
                        .setIssueType(toGrpcIssueType(issueTypeDto))
                        .setActorUserId(context.userContext().userId()))
                .build();

    }

    /**
     * Преобразует REST-запрос создания workflow проекта в gRPC-запрос.
     *
     * @param projectId идентификатор проекта
     * @param request   тело REST-запроса со статусами, переходами и типами задач
     * @param context   текущий контекст сквозной трассировки и пользователя шлюза
     * @return сформированный gRPC-объект {@link CreateWorkflowRequest}
     */
    public CreateWorkflowRequest toCreateWorkflowGrpcRequest(
            UUID projectId,
            CreateWorkflowRequestDto request,
            GatewayContext context
    ) {
        return CreateWorkflowRequest.newBuilder()
                .setHeader(toGrpcHeader(context))
                .setBody(CreateWorkflowRequestBody.newBuilder()
                        .setProjectId(projectId.toString())
                        .setName(request.getName())
                        .addAllIssueTypes(request.getIssueTypes().stream().map(this::toGrpcIssueType).toList())
                        .addAllStatuses(request.getStatuses().stream().map(this::toGrpcStatusCreateRequest).toList())
                        .addAllTransitions(request.getTransitions().stream().map(this::toGrpcTransitionCreateRequest).toList())
                        .setActorUserId(context.userContext().userId()))
                .build();
    }

    /**
     * Преобразует REST-запрос добавления статуса в gRPC-запрос.
     *
     * @param projectId  идентификатор проекта
     * @param workflowId идентификатор workflow проекта
     * @param request    добавляемый статус
     * @param context    текущий контекст сквозной трассировки и пользователя шлюза
     * @return сформированный gRPC-объект {@link AddWorkflowStatusRequest}
     */
    public AddWorkflowStatusRequest toAddWorkflowStatusGrpcRequest(
            UUID projectId,
            UUID workflowId,
            CreateWorkflowStatusRequestDto request,
            GatewayContext context
    ) {
        return AddWorkflowStatusRequest.newBuilder()
                .setHeader(toGrpcHeader(context))
                .setBody(AddWorkflowStatusRequestBody.newBuilder()
                        .setProjectId(projectId.toString())
                        .setWorkflowId(workflowId.toString())
                        .setStatus(toGrpcStatusCreateRequest(request))
                        .setActorUserId(context.userContext().userId()))
                .build();
    }

    /**
     * Преобразует REST-запрос добавления перехода в gRPC-запрос.
     *
     * @param projectId  идентификатор проекта
     * @param workflowId идентификатор workflow проекта
     * @param request    добавляемый переход
     * @param context    текущий контекст сквозной трассировки и пользователя шлюза
     * @return сформированный gRPC-объект {@link AddWorkflowTransitionRequest}
     */
    public AddWorkflowTransitionRequest toAddWorkflowTransitionGrpcRequest(
            UUID projectId,
            UUID workflowId,
            CreateWorkflowTransitionRequestDto request,
            GatewayContext context
    ) {
        return AddWorkflowTransitionRequest.newBuilder()
                .setHeader(toGrpcHeader(context))
                .setBody(AddWorkflowTransitionRequestBody.newBuilder()
                        .setProjectId(projectId.toString())
                        .setWorkflowId(workflowId.toString())
                        .setTransition(toGrpcTransitionCreateRequest(request))
                        .setActorUserId(context.userContext().userId()))
                .build();
    }

    /**
     * Преобразует параметры REST-запроса в gRPC-запрос всех workflow проекта.
     *
     * @param projectId идентификатор проекта
     * @param context   текущий контекст сквозной трассировки и пользователя шлюза
     * @return сформированный gRPC-объект {@link ListProjectWorkflowsRequest}
     */
    public ListProjectWorkflowsRequest toListProjectWorkflowsGrpcRequest(UUID projectId, GatewayContext context) {
        return ListProjectWorkflowsRequest.newBuilder()
                .setHeader(toGrpcHeader(context))
                .setBody(ListProjectWorkflowsRequestBody.newBuilder()
                        .setProjectId(projectId.toString())
                        .setActorUserId(context.userContext().userId()))
                .build();
    }

    /**
     * Преобразует gRPC-ответ со всеми workflow проекта в REST DTO.
     *
     * @param response ответ workflow-service
     * @return REST DTO {@link ProjectWorkflowsResponseDto}
     */
    public ProjectWorkflowsResponseDto toProjectWorkflowsResponseRestDto(ListProjectWorkflowsResponse response) {
        ProjectWorkflowsResponseDto dto = new ProjectWorkflowsResponseDto();
        dto.setItems(response.getWorkflowsList().stream().map(this::toProjectWorkflowDto).toList());
        return dto;
    }

    /**
     * Преобразует ответ gRPC сервиса Workflow в REST DTO формата ответа.
     *
     * @param workflowResponse полученный от gRPC-сервиса объект {@link WorkflowResponse} с данными структуры процессов
     * @return заполненный REST DTO объект {@link WorkflowResponseDto} для отправки клиенту
     */
    public WorkflowResponseDto toWorkflowResponseRestDto(WorkflowResponse workflowResponse) {
        WorkflowResponseDto dto = new WorkflowResponseDto();

        dto.setId(UUID.fromString(workflowResponse.getId()));
        dto.setName(workflowResponse.getName());
        dto.setVersion(workflowResponse.getVersion());
        dto.setCreatedAt(toOffsetDateTime(workflowResponse.getCreatedAt()));
        dto.setUpdatedAt(toOffsetDateTime(workflowResponse.getUpdatedAt()));

        dto.setStatuses(
                workflowResponse.getStatusesList()
                        .stream()
                        .map(this::toWorkflowStatusDto)
                        .toList()
        );

        dto.setTransitions(
                workflowResponse.getTransitionsList()
                        .stream()
                        .map(this::toWorkflowTransitionDto)
                        .toList()
        );

        return dto;
    }

    /**
     * Преобразует отдельный статус workflow из gRPC-сообщения в REST DTO объект ответа.
     *
     * @param status полученный по gRPC объект статуса {@link WorkflowStatus}
     * @return заполненный REST DTO объект {@link WorkflowStatusDto}
     */
    private WorkflowStatusDto toWorkflowStatusDto(WorkflowStatus status) {
        WorkflowStatusDto dto = new WorkflowStatusDto();

        dto.setId(UUID.fromString(status.getId()));
        dto.setStatusKey(status.getStatusKey());
        dto.setName(status.getName());
        dto.setCategory(toRestStatusCategory(status.getCategory()));
        dto.setSortOrder(status.getSortOrder());
        dto.setCreatedAt(toOffsetDateTime(status.getCreatedAt()));
        dto.setUpdatedAt(toOffsetDateTime(status.getUpdatedAt()));

        return dto;
    }

    /**
     * Преобразует перечисление (Enum) категории статуса из Protobuf контракта в очищенную REST-строку.
     * Исключает системные префиксы gRPC-слоя (например, STATUS_CATEGORY_DONE переводит в DONE).
     *
     * @param category объект статуса {@link StatusCategory}, содержащий gRPC-категорию
     * @return строковое представление категории для REST контракта
     */
    public String toRestStatusCategory(StatusCategory category) {
        if (category == null
                || category == StatusCategory.STATUS_CATEGORY_UNSPECIFIED
                || category == StatusCategory.UNRECOGNIZED) {
            return "UNKNOWN";
        }

        String name = category.name();
        return name.startsWith(STATUS_CATEGORY_PREFIX)
                ? name.substring(STATUS_CATEGORY_PREFIX.length())
                : name;
    }

    /**
     * Преобразует отдельный переход workflow из gRPC-сообщения в REST DTO объект ответа.
     *
     * @param transition полученный по gRPC объект перехода {@link WorkflowTransition}
     * @return заполненный REST DTO объект {@link WorkflowTransitionDto}
     */
    private WorkflowTransitionDto toWorkflowTransitionDto(WorkflowTransition transition) {
        WorkflowTransitionDto dto = new WorkflowTransitionDto();

        dto.setId(UUID.fromString(transition.getId()));
        dto.setFromStatusId(UUID.fromString(transition.getFromStatusId()));
        dto.setToStatusId(UUID.fromString(transition.getToStatusId()));
        dto.setName(transition.getName());
        dto.setSortOrder(transition.getSortOrder());
        dto.setHidden(transition.getIsHidden());
        dto.setCreatedAt(toOffsetDateTime(transition.getCreatedAt()));
        dto.setUpdatedAt(toOffsetDateTime(transition.getUpdatedAt()));

        return dto;
    }

    public ProjectWorkflowDto toProjectWorkflowDto(ProjectWorkflow projectWorkflow) {
        ProjectWorkflowDto dto = new ProjectWorkflowDto();

        dto.setWorkflow(toWorkflowResponseRestDto(projectWorkflow.getWorkflow()));
        dto.setIssueTypes(projectWorkflow.getIssueTypesList().stream()
                .map(this::toRestIssueType)
                .filter(Objects::nonNull)
                .toList());
        dto.setShared(projectWorkflow.getIsShared());

        return dto;
    }

    private WorkflowStatusCreateRequest toGrpcStatusCreateRequest(CreateWorkflowStatusRequestDto status) {
        return WorkflowStatusCreateRequest.newBuilder()
                .setStatusKey(status.getStatusKey())
                .setName(status.getName())
                .setCategory(toGrpcStatusCategory(status.getCategory()))
                .setSortOrder(status.getSortOrder())
                .build();
    }

    private WorkflowTransitionCreateRequest toGrpcTransitionCreateRequest(CreateWorkflowTransitionRequestDto transition) {
        return WorkflowTransitionCreateRequest.newBuilder()
                .setFromStatusKey(transition.getFromStatusKey())
                .setToStatusKey(transition.getToStatusKey())
                .setName(transition.getName())
                .setSortOrder(transition.getSortOrder())
                .setIsHidden(Boolean.TRUE.equals(transition.getHidden()))
                .build();
    }

    private StatusCategory toGrpcStatusCategory(WorkflowStatusCategoryDto category) {
        return switch (category) {
            case TODO -> StatusCategory.STATUS_CATEGORY_TODO;
            case IN_PROGRESS -> StatusCategory.STATUS_CATEGORY_IN_PROGRESS;
            case DONE -> StatusCategory.STATUS_CATEGORY_DONE;
        };
    }

    // Тип, которого REST-контракт не знает, не отдаётся: так же workflow-service молчит о непривязанных типах.
    private IssueTypeDto toRestIssueType(IssueType issueType) {
        return switch (issueType) {
            case ISSUE_TYPE_TASK -> IssueTypeDto.TASK;
            case ISSUE_TYPE_BUG -> IssueTypeDto.BUG;
            case ISSUE_TYPE_STORY -> IssueTypeDto.STORY;
            default -> null;
        };
    }

    private Header toGrpcHeader(GatewayContext context) {
        return Header.newBuilder()
                .setRequestId(context.requestId())
                .setNodeId(context.nodeId())
                .build();
    }

    /// ================ UTILITY ====================

    public OffsetDateTime toOffsetDateTime(Timestamp timestamp) {
        if (timestamp == null) {
            return null;
        }
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos())
                .atOffset(ZoneOffset.UTC);
    }

    /**
     * Преобразует тип задачи REST API в соответствующее
     * перечисление gRPC-контракта.
     *
     * @param issueTypeDto тип задачи из REST API
     * @return соответствующее значение {@link IssueType}
     */
    public IssueType toGrpcIssueType(IssueTypeDto issueTypeDto) {
        return switch (issueTypeDto) {
            case TASK -> IssueType.ISSUE_TYPE_TASK;
            case BUG -> IssueType.ISSUE_TYPE_BUG;
            case STORY -> IssueType.ISSUE_TYPE_STORY;
        };
    }
}
