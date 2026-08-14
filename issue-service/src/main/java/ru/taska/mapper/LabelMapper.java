package ru.taska.mapper;

import com.google.protobuf.Timestamp;
import org.springframework.stereotype.Component;
import ru.taska.api.issue.v1.AddIssueLabelRequest;
import ru.taska.api.issue.v1.AddIssueLabelResponse;
import ru.taska.api.issue.v1.CreateProjectLabelRequest;
import ru.taska.api.issue.v1.DeleteProjectLabelRequest;
import ru.taska.api.issue.v1.DeleteProjectLabelResponse;
import ru.taska.api.issue.v1.ListIssueLabelsRequest;
import ru.taska.api.issue.v1.ListIssueLabelsResponse;
import ru.taska.api.issue.v1.ListProjectLabelsRequest;
import ru.taska.api.issue.v1.ListProjectLabelsResponse;
import ru.taska.api.issue.v1.ProjectLabelResponse;
import ru.taska.api.issue.v1.RemoveIssueLabelRequest;
import ru.taska.api.issue.v1.RemoveIssueLabelResponse;
import ru.taska.api.issue.v1.UpdateProjectLabelRequest;
import ru.taska.domain.dto.LabelCommands;
import ru.taska.domain.dto.LabelResponses;
import ru.taska.domain.labels.IssueLabels;
import ru.taska.domain.labels.ProjectLabels;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class LabelMapper {

    // ===== Proto Request →  DTO Request =====

    public LabelCommands.CreateProjectLabelRequestDto toCreateProjectLabelRequestDto(CreateProjectLabelRequest protoRequest) {
        var body = protoRequest.getBody();
        return new LabelCommands.CreateProjectLabelRequestDto(
                UUID.fromString(body.getProjectId()),
                body.getName(),
                body.getColor(),
                UUID.fromString(body.getCreatedBy())
        );
    }

    public LabelCommands.UpdateProjectLabelRequestDto toUpdateProjectLabelRequestDto(UpdateProjectLabelRequest protoRequest) {
        var body = protoRequest.getBody();
        return new LabelCommands.UpdateProjectLabelRequestDto(
                UUID.fromString(body.getLabelId()),
                UUID.fromString(body.getProjectId()),
                body.getName(),
                body.getColor(),
                UUID.fromString(body.getActorUserId())
        );
    }

    public LabelCommands.DeleteProjectLabelRequestDto toDeleteProjectLabelRequestDto(DeleteProjectLabelRequest protoRequest) {
        var body = protoRequest.getBody();
        return new LabelCommands.DeleteProjectLabelRequestDto(
                UUID.fromString(body.getLabelId()),
                UUID.fromString(body.getProjectId()),
                UUID.fromString(body.getActorUserId())

        );
    }

    public LabelCommands.ListProjectLabelsRequestDto toListProjectLabelsRequestDto(ListProjectLabelsRequest protoRequest) {
        var body = protoRequest.getBody();
        return new LabelCommands.ListProjectLabelsRequestDto(
                UUID.fromString(body.getProjectId()),
                UUID.fromString(body.getActorUserId())
        );
    }

    public LabelCommands.AddIssueLabelRequestDto toAddIssueLabelRequestDto(AddIssueLabelRequest protoRequest) {
        var body = protoRequest.getBody();
        return new LabelCommands.AddIssueLabelRequestDto(
                UUID.fromString(body.getIssueId()),
                UUID.fromString(body.getLabelId()),
                UUID.fromString(body.getActorUserId())
        );
    }

    public LabelCommands.RemoveIssueLabelRequestDto toRemoveIssueLabelRequestDto(RemoveIssueLabelRequest protoRequest) {
        var body = protoRequest.getBody();
        return new LabelCommands.RemoveIssueLabelRequestDto(
                UUID.fromString(body.getIssueId()),
                UUID.fromString(body.getLabelId()),
                UUID.fromString(body.getActorUserId())
        );
    }

    public LabelCommands.ListIssueLabelsRequestDto toListIssueLabelsRequestDto(ListIssueLabelsRequest protoRequest) {
        var body = protoRequest.getBody();
        return new LabelCommands.ListIssueLabelsRequestDto(
                UUID.fromString(body.getIssueId()),
                UUID.fromString(body.getActorUserId())
        );
    }

    // ===== Domain → Response DTO =====

    public LabelResponses.ProjectLabelInfo toProjectLabelInfo(ProjectLabels label) {
        if (label == null) return null;
        return new LabelResponses.ProjectLabelInfo(
                label.getId(),
                label.getProjectId(),
                label.getName(),
                label.getColor(),
                label.getCreatedBy(),
                label.getCreatedAt(),
                label.getDeletedAt()
        );
    }

    public LabelResponses.ListProjectLabelResponseDto toListProjectLabelResponseDto(List<ProjectLabels> labels) {
        var infos = labels.stream()
                .map(this::toProjectLabelInfo)
                .toList();
        return LabelResponses.ListProjectLabelResponseDto.of(infos);
    }

    public LabelResponses.ListIssueLabelResponseDto toListIssueLabelResponseDto(List<ProjectLabels> labels) {
        var infos = labels.stream()
                .map(this::toProjectLabelInfo)
                .toList();
        return LabelResponses.ListIssueLabelResponseDto.of(infos);
    }

    // ===== Request DTO → Domain (Entity) =====

    public ProjectLabels toEntity(LabelCommands.CreateProjectLabelRequestDto requestDto) {
        return ProjectLabels.builder()
                .id(UUID.randomUUID())
                .projectId(requestDto.projectId())
                .name(requestDto.name().trim())
                .color(requestDto.color().toUpperCase())
                .createdBy(requestDto.actorUserId())
                .createdAt(Instant.now())
                .build();
    }

    public IssueLabels toEntity(LabelCommands.AddIssueLabelRequestDto requestDto) {
        return IssueLabels.builder()
                .id(UUID.randomUUID())
                .issueId(requestDto.issueId())
                .labelId(requestDto.labelId())
                .createdBy(requestDto.actorUserId())
                .createdAt(Instant.now())
                .build();
    }

    public void updateEntity(ProjectLabels label, LabelCommands.UpdateProjectLabelRequestDto requestDto) {
        label.setName(requestDto.name().trim());
        label.setColor(requestDto.color().toUpperCase());
    }

    // ===== DTO Response → Proto Response=====

    /// ===== Взаимодействие с PROJECT =====

    /**
     * Используется для CreateProjectLabel и UpdateProjectLabel
     */
    public ProjectLabelResponse toProjectLabelProtoResponse(LabelResponses.ProjectLabelInfo info) {
        if (info == null) return ProjectLabelResponse.getDefaultInstance();

        var builder = ProjectLabelResponse.newBuilder()
                .setId(info.id().toString())
                .setProjectId(info.projectId().toString())
                .setName(info.name())
                .setColor(info.color())
                .setCreatedBy(info.createdBy().toString());

        if (info.createdAt() != null) {
            builder.setCreatedAt(toTimestamp(info.createdAt()));
        }

        if (info.deletedAt() != null) {
            builder.setDeletedAt(toTimestamp(info.deletedAt()));
        }

        return builder.build();
    }

    public DeleteProjectLabelResponse toDeleteProjectLabelProtoResponse(LabelResponses.DeleteProjectLabelResponseDto responseDto) {
        var builder = DeleteProjectLabelResponse.newBuilder()
                .setLabelId(responseDto.labelId().toString())
                .setProjectId(responseDto.projectId().toString());

        if (responseDto.deletedAt() != null) {
            builder.setDeletedAt(toTimestamp(responseDto.deletedAt()));
        }

        return builder.build();
    }


    public ListProjectLabelsResponse toListProjectLabelsProtoResponse(LabelResponses.ListProjectLabelResponseDto responseDto) {
        return ListProjectLabelsResponse.newBuilder()
                .addAllLabels(
                        responseDto.labels().stream()
                                .map(this::toProjectLabelProtoResponse)
                                .toList()
                )
                .setTotalCount(responseDto.totalCount())
                .build();
    }

    /// ===== Взаимодействие меток с ISSUE =====
    ///
    public AddIssueLabelResponse toAddIssueLabelProtoResponse(LabelResponses.AddIssueLabelResponseDto responseDto) {
        var builder = AddIssueLabelResponse.newBuilder()
                .setIssueId(responseDto.issueId().toString())
                .setLabelId(responseDto.labelId().toString())
                .setCreatedBy(responseDto.createdBy().toString());

        if (responseDto.createdAt() != null) {
            builder.setCreatedAt(toTimestamp(responseDto.createdAt()));
        }

        return builder.build();
    }

    public RemoveIssueLabelResponse toRemoveIssueLabelProtoResponse(LabelResponses.RemoveIssueLabelResponseDto responseDto) {
        var builder = RemoveIssueLabelResponse.newBuilder()
                .setIssueId(responseDto.issueId().toString())
                .setLabelId(responseDto.labelId().toString());

        return builder.build();
    }

    public ListIssueLabelsResponse toListIssueLabelsProtoResponse(LabelResponses.ListIssueLabelResponseDto responseDto) {
        return ListIssueLabelsResponse.newBuilder()
                .addAllLabels(
                        responseDto.labels().stream()
                                .map(this::toProjectLabelProtoResponse)
                                .toList()
                )
                .build();
    }

    // ===== Utils =====

    private Timestamp toTimestamp(Instant instant) {
        if (instant == null) return Timestamp.getDefaultInstance();
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }
}
