package ru.taska.dto;

import lombok.Builder;
import lombok.Data;
import ru.taska.domain.IssueType;

import java.util.List;
import java.util.UUID;

@Data
@Builder
public class WorkflowCreationDto {
    private UUID projectId;
    private String name;
    private List<IssueType> issueTypes;
    private List<CreateWorkflowStatusDto> statuses;
    private List<CreateWorkflowTransitionDto> transitions;
}
