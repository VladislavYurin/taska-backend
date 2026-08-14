package ru.taska.service;

import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.domain.dto.LabelCommands;
import ru.taska.domain.dto.LabelResponses;
import ru.taska.domain.labels.IssueLabels;
import ru.taska.domain.labels.ProjectLabels;

import java.util.UUID;

public interface LabelService {

    // ADMIN: Управление метками проекта
    Mono<LabelResponses.ProjectLabelInfo> createProjectLabel(
            String requestId,
            String nodeId,
            LabelCommands.CreateProjectLabelRequestDto requestDto
    );
    Mono<LabelResponses.ProjectLabelInfo> updateProjectLabel(
            String requestId,
            String nodeId,
            LabelCommands.UpdateProjectLabelRequestDto requestDto
    );
    Mono<LabelResponses.DeleteProjectLabelResponseDto> deleteProjectLabel(
            String requestId,
            String nodeId,
            LabelCommands.DeleteProjectLabelRequestDto requestDto
    );

    // MEMBER+: Управление метками задачи
    Mono<LabelResponses.AddIssueLabelResponseDto> addIssueLabel(
            String requestId,
            String nodeId,
            LabelCommands.AddIssueLabelRequestDto requestDto
    );
    Mono<LabelResponses.RemoveIssueLabelResponseDto> removeIssueLabel(
            String requestId,
            String nodeId,
            LabelCommands.RemoveIssueLabelRequestDto requestDto
    );

    // VIEWER+: Просмотр меток
    Mono<LabelResponses.ListIssueLabelResponseDto> listIssueLabels(
            String requestId,
            String nodeId,
            LabelCommands.ListIssueLabelsRequestDto requestDto
    );
    Mono<LabelResponses.ListProjectLabelResponseDto> listProjectLabels(
            String requestId,
            String nodeId,
            LabelCommands.ListProjectLabelsRequestDto requestDto
    );

}
