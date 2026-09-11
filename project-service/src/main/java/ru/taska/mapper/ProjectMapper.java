package ru.taska.mapper;

import com.google.protobuf.Timestamp;
import org.mapstruct.AfterMapping;
import org.mapstruct.CollectionMappingStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValueCheckStrategy;
import org.mapstruct.ReportingPolicy;
import ru.taska.api.common.v1.GlobalRoleProto;
import ru.taska.api.project.v1.ProjectContextResponse;
import ru.taska.api.project.v1.ProjectLabelResponse;
import ru.taska.api.project.v1.ProjectMemberDetailsResponse;
import ru.taska.api.project.v1.ProjectResponse;
import ru.taska.api.project.v1.ProjectShortInfo;
import ru.taska.api.project.v1.ProjectWorkflowEntryResponse;
import ru.taska.api.project.v1.WorkflowDataResponse;
import ru.taska.api.project.v1.WorkflowStatusResponse;
import ru.taska.api.project.v1.WorkflowTransitionResponse;
import ru.taska.domain.GlobalRole;
import ru.taska.domain.Project;
import ru.taska.domain.dto.ProjectCheckMembershipDto;
import ru.taska.domain.projection.ProjectInfo;
import ru.taska.domain.dto.ProjectMemberDetailsDto;
import ru.taska.domain.dto.projectContext.ProjectContextDto;
import ru.taska.domain.dto.projectContext.ProjectLabelDto;
import ru.taska.domain.dto.projectContext.ProjectWorkflowDto;
import ru.taska.domain.dto.projectContext.WorkflowDto;
import ru.taska.domain.dto.projectContext.WorkflowStatusDto;
import ru.taska.domain.dto.projectContext.WorkflowTransitionDto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING,
        collectionMappingStrategy = CollectionMappingStrategy.ADDER_PREFERRED,
        unmappedTargetPolicy = ReportingPolicy.IGNORE,
        uses = {ProjectMemberMapper.class}
)
public interface ProjectMapper {
    /**
     * Маппинг {@link Project} в {@link ProjectResponse}
     * param {@link Project}
     * return {@link ProjectResponse}
     */
    @Mapping(target = "id", source = "id")
    @Mapping(target = "projectKey", source = "projectKey")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "createdBy", source = "createdBy")
    @Mapping(target = "createdAt", source = "createdAt")
    @Mapping(target = "updatedAt", source = "updatedAt")
    @Mapping(target = "archivedAt", source = "archivedAt", nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS)
    @Mapping(target = "description", source = "description", nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS)
    @Mapping(target = "color", source = "color", nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS)
    ProjectResponse toProjectResponse(Project project);

    /**
     * Маппинг {@link ProjectCheckMembershipDto} в {@link ProjectResponse}
     * param {@link ProjectCheckMembershipDto}
     * return {@link ProjectResponse}
     */
    @Mapping(target = "id", source = "project_id")
    @Mapping(target = "projectKey", source = "project_key")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "createdBy", source = "created_by")
    @Mapping(target = "createdAt", source = "created_at")
    @Mapping(target = "updatedAt", source = "updated_at")
    @Mapping(target = "archivedAt", source = "archived_at", nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS)
    @Mapping(target = "currentUserRole", source = "role", nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS)
    @Mapping(target = "description", source = "description", nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS)
    @Mapping(target = "color", source = "color", nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS)
    ProjectResponse toProjectResponse(ProjectCheckMembershipDto project);

    @Mapping(target = "id", source = "project_id")
    @Mapping(target = "projectKey", source = "project_key")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "createdBy", source = "created_by")
    @Mapping(target = "createdAt", source = "created_at")
    @Mapping(target = "updatedAt", source = "updated_at")
    @Mapping(target = "archivedAt", source = "archived_at")
    @Mapping(target = "description", source = "description")
    @Mapping(target = "color", source = "color")
    Project toProject(ProjectCheckMembershipDto dto);

    Map<String, ProjectShortInfo> toProtoMap(Map<UUID, ProjectInfo> uuidProjectInfoMap);

    ProjectShortInfo toProto(ProjectInfo domain);

    /**
     * Маппинг {@link ProjectMemberDetailsDto} → {@link ProjectMemberDetailsResponse}.
     *
     * <p>Реализацию генерирует MapStruct для этого интерфейса (а не делегирует в
     * {@link ProjectMemberMapper}), поэтому проверки на {@code null} объявлены здесь явно:
     * protobuf-сеттеры не принимают {@code null}, а у участника может не быть аватара,
     * отображаемого имени или e-mail.</p>
     *
     * @param dto DTO участника проекта
     * @return gRPC-ответ с данными участника
     */
    @Mapping(target = "displayName", nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS)
    @Mapping(target = "email",       nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS)
    @Mapping(target = "avatar",      nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS)
    @Mapping(target = "addedAt", source = "addedAt",
            nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS,
            qualifiedByName = "instantToTimestamp")
    ProjectMemberDetailsResponse toProjectMemberResponse(ProjectMemberDetailsDto dto);

    /**
     * Маппинг {@link ProjectLabelDto} → {@link ProjectLabelResponse}.
     *
     * <p>Поле {@code id} типа {@link UUID} конвертируется в {@code String}
     * через {@link #mapUuidToString(UUID)} — MapStruct подхватывает
     * {@code default}-метод автоматически.</p>
     *
     * @param dto DTO метки проекта
     * @return gRPC-ответ с данными метки
     */
    ProjectLabelResponse toProjectLabelResponse(ProjectLabelDto dto);

    /**
     * Маппинг {@link ProjectWorkflowDto} → {@link ProjectWorkflowEntryResponse}.
     *
     * <p>Вложенный {@code workflow} мапится через
     * {@link #toWorkflowDataResponse(WorkflowDto)} — MapStruct вызывает
     * его автоматически.</p>
     *
     * @param dto DTO workflow, привязанного к типу задачи
     * @return gRPC-ответ с записью workflow
     */
    ProjectWorkflowEntryResponse toProjectWorkflowResponse(ProjectWorkflowDto dto);

    /**
     * Маппинг {@link WorkflowDto} → {@link WorkflowDataResponse}.
     *
     * <p>Списки {@code statuses} и {@code transitions} дописывает
     * {@link #addWorkflowStatusesAndTransitions(WorkflowDto, WorkflowDataResponse.Builder)}.
     * Поля {@code createdAt}/{@code updatedAt} конвертируются
     * через {@link #mapInstantToTimestamp(Instant)}.</p>
     *
     * @param dto DTO workflow с версией, статусами и переходами
     * @return gRPC-ответ с полными данными workflow
     */
    WorkflowDataResponse toWorkflowDataResponse(WorkflowDto dto);

    /**
     * Списки {@code statuses} и {@code transitions} для {@link #toWorkflowDataResponse(WorkflowDto)}.
     *
     * <p>Сам MapStruct их не заполняет: у repeated-полей protobuf-билдера нет сеттера,
     * только {@code addStatuses}/{@code getStatusesList}, и без protobuf-SPI MapStruct
     * не видит в них свойство. При {@code unmappedTargetPolicy = IGNORE} об этом
     * не предупреждает и сборка (TAS-261).</p>
     *
     * @param dto    DTO workflow со статусами и переходами
     * @param target билдер ответа, который собирает {@link #toWorkflowDataResponse(WorkflowDto)}
     */
    @AfterMapping
    default void addWorkflowStatusesAndTransitions(WorkflowDto dto, @MappingTarget WorkflowDataResponse.Builder target) {
        if (dto.statuses() != null) {
            dto.statuses().forEach(status -> target.addStatuses(toWorkflowStatusResponse(status)));
        }
        if (dto.transitions() != null) {
            dto.transitions().forEach(transition -> target.addTransitions(toWorkflowTransitionResponse(transition)));
        }
    }

    /**
     * Маппинг {@link WorkflowStatusDto} → {@link WorkflowStatusResponse}.
     *
     * @param dto DTO статуса workflow
     * @return gRPC-ответ с данными статуса
     */
    WorkflowStatusResponse toWorkflowStatusResponse(WorkflowStatusDto dto);

    /**
     * Маппинг {@link WorkflowTransitionDto} → {@link WorkflowTransitionResponse}.
     *
     * @param dto DTO перехода между статусами workflow
     * @return gRPC-ответ с данными перехода
     */
    WorkflowTransitionResponse toWorkflowTransitionResponse(WorkflowTransitionDto dto);


    /**
     * Сборка агрегированного ответа {@link ProjectContextResponse} из
     * {@link ProjectContextDto}.
     *
     * @param dto агрегированный DTO контекста проекта (проект, участники, метки, workflow)
     * @return gRPC-ответ с полным контекстом проекта
     */
    default ProjectContextResponse toProjectContextResponse(ProjectContextDto dto) {
        ProjectContextResponse.Builder builder = ProjectContextResponse.newBuilder()
                .setProject(toProjectResponse(dto.project()));

        dto.members().forEach(m -> builder.addMembers(toProjectMemberResponse(m)));
        dto.labels().forEach(l -> builder.addLabels(toProjectLabelResponse(l)));
        dto.workflows().forEach(w -> builder.addWorkflows(toProjectWorkflowResponse(w)));

        return builder.build();
    }

    /// ───────── HELPERS ─────────

    /**
     * Конвертация UUID в String
     */
    default String mapUuidToString(UUID uuid) {
        return uuid != null ? uuid.toString() : null;
    }

    /**
     * Конвертация Java Instant в Protobuf Timestamp
     */
    default Timestamp mapInstantToTimestamp(Instant instant) {
        if (instant == null) {
            return Timestamp.getDefaultInstance();
        }
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }

    default GlobalRole toGlobalRole(GlobalRoleProto proto){
        if (proto == null) {
            return GlobalRole.USER;
        }
        return switch (proto) {
            case GLOBAL_ROLE_GLOBAL_ADMIN -> GlobalRole.GLOBAL_ADMIN;
            case GLOBAL_ROLE_USER -> GlobalRole.USER;
            default -> GlobalRole.USER;
        };
    }
}
