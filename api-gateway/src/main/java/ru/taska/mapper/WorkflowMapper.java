package ru.taska.mapper;

import org.springframework.stereotype.Component;
import ru.taska.api.common.v1.Header;
import ru.taska.api.workflow.v1.GetWorkflowForProjectRequest;
import ru.taska.api.workflow.v1.GetWorkflowForProjectRequestBody;
import ru.taska.api.workflow.v1.IssueType;
import ru.taska.api.workflow.v1.StatusCategory;
import ru.taska.api.workflow.v1.WorkflowResponse;
import ru.taska.api.workflow.v1.WorkflowStatus;
import ru.taska.api.workflow.v1.WorkflowTransition;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.dto.IssueTypeDto;
import ru.taska.domain.dto.WorkflowResponseDto;
import ru.taska.domain.dto.WorkflowStatusDto;
import ru.taska.domain.dto.WorkflowTransitionDto;

import java.time.OffsetDateTime;
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
        dto.setCreatedAt(OffsetDateTime.parse(workflowResponse.getCreatedAt()));
        dto.setUpdatedAt(OffsetDateTime.parse(workflowResponse.getUpdatedAt()));

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
        dto.setCreatedAt(OffsetDateTime.parse(status.getCreatedAt()));
        dto.setUpdatedAt(OffsetDateTime.parse(status.getUpdatedAt()));

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
        dto.setCreatedAt(OffsetDateTime.parse(transition.getCreatedAt()));
        dto.setUpdatedAt(OffsetDateTime.parse(transition.getUpdatedAt()));

        return dto;
    }

}
