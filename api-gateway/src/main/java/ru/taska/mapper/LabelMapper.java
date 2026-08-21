package ru.taska.mapper;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import ru.taska.api.issue.v1.AddIssueLabelResponse;
import ru.taska.api.issue.v1.ListIssueLabelsResponse;
import ru.taska.api.issue.v1.ListProjectLabelsResponse;
import ru.taska.api.issue.v1.ProjectLabelResponse;
import ru.taska.domain.dto.AddIssueLabelResponseDto;
import ru.taska.domain.dto.IssueLabelResponseDto;
import ru.taska.domain.dto.ListIssueLabelsResponseDto;
import ru.taska.domain.dto.ListProjectLabelsResponseDto;
import ru.taska.domain.dto.ProjectLabelResponseDto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class LabelMapper {

    //============== Proto Response -> Rest Response ===============

    public ProjectLabelResponseDto toRestProjectLabelResponse(ProjectLabelResponse protoDto) {
        ProjectLabelResponseDto restDto = new ProjectLabelResponseDto();
        restDto.setId(UUID.fromString(protoDto.getId()));
        restDto.setProjectId(UUID.fromString(protoDto.getProjectId()));
        restDto.setName(protoDto.getName());
        restDto.setColor(protoDto.getColor());
        restDto.setCreatedBy(UUID.fromString(protoDto.getCreatedBy()));
        restDto.createdAt(toOffsetDateTime(protoDto.getCreatedAt()));
        restDto.deletedAt(toOffsetDateTime(protoDto.getDeletedAt()));
        return restDto;
    }

    public ListProjectLabelsResponseDto toRestListProjectLabelsResponse(ListProjectLabelsResponse protoDto) {
        ListProjectLabelsResponseDto restDto = new ListProjectLabelsResponseDto();

        List<ProjectLabelResponseDto> items = protoDto.getLabelsList().stream()
                .map(this::toRestProjectLabelResponse)
                .collect(Collectors.toList());

        restDto.setItems(items);
        restDto.setTotalCount(protoDto.getTotalCount());
        return restDto;
    }

    public AddIssueLabelResponseDto toRestAddIssueLabelResponse(AddIssueLabelResponse protoDto) {
        AddIssueLabelResponseDto restDto = new AddIssueLabelResponseDto();
        restDto.setIssueId(UUID.fromString(protoDto.getIssueId()));
        restDto.setLabelId(UUID.fromString(protoDto.getLabelId()));
        restDto.setCreatedBy(UUID.fromString(protoDto.getCreatedBy()));
        restDto.setCreatedAt(toOffsetDateTime(protoDto.getCreatedAt()));
        return restDto;
    }

    public IssueLabelResponseDto  toRestIssueLabelResponse(ProjectLabelResponse protoDto) {
        IssueLabelResponseDto  restDto = new IssueLabelResponseDto ();
        restDto.setId(UUID.fromString(protoDto.getId()));
        restDto.setName(protoDto.getName());
        restDto.setColor(protoDto.getColor());
        return restDto;
    }

    public ListIssueLabelsResponseDto toRestListIssueLabelsResponse(ListIssueLabelsResponse protoDto) {
        ListIssueLabelsResponseDto dto = new ListIssueLabelsResponseDto();

        List<IssueLabelResponseDto> items = protoDto.getLabelsList().stream()
                .map(this::toRestIssueLabelResponse)
                .collect(Collectors.toList());

        dto.setItems(items);
        dto.setTotalCount(items.size());
        return dto;
    }

    // ===== Utils =====

    private OffsetDateTime toOffsetDateTime(com.google.protobuf.Timestamp timestamp) {
        if (timestamp == null || (timestamp.getSeconds() == 0 && timestamp.getNanos() == 0)) {
            return null;
        }
        return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos())
                .atOffset(ZoneOffset.UTC);
    }
}
