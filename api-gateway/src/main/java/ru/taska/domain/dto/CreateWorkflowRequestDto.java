package ru.taska.domain.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import lombok.Getter;

/** Strict creation command: omitted mappings are empty; explicit null is invalid. */
@Getter
public class CreateWorkflowRequestDto {
    @NotBlank @Size(max = 100) private String name;
    @NotNull private List<String> issueTypes;
    @NotNull @Valid private List<@NotNull CreateWorkflowStatusRequestDto> statuses;
    @NotNull @Valid private List<@NotNull CreateWorkflowTransitionRequestDto> transitions;
    private Map<String, String> statusMappings = Map.of();

    public CreateWorkflowRequestDto() {}

    public CreateWorkflowRequestDto(String name, List<String> issueTypes, List<CreateWorkflowStatusRequestDto> statuses,
            List<CreateWorkflowTransitionRequestDto> transitions) {
        setName(name); setIssueTypes(issueTypes); setStatuses(statuses); setTransitions(transitions);
    }

    @JsonSetter(nulls = Nulls.FAIL)
    public void setName(String value) { name = value; }
    @JsonSetter(nulls = Nulls.FAIL)
    public void setIssueTypes(List<String> value) { issueTypes = WorkflowCommandValidation.issueTypes(value); }
    @JsonSetter(nulls = Nulls.FAIL)
    public void setStatuses(List<CreateWorkflowStatusRequestDto> value) { statuses = value; }
    @JsonSetter(nulls = Nulls.FAIL)
    public void setTransitions(List<CreateWorkflowTransitionRequestDto> value) { transitions = value; }
    @JsonSetter(nulls = Nulls.FAIL)
    public void setStatusMappings(Map<String, String> value) { statusMappings = WorkflowCommandValidation.statusMappings(value); }

    public CreateWorkflowRequestDto name(String value) { setName(value); return this; }
    public CreateWorkflowRequestDto issueTypes(List<String> value) { setIssueTypes(value); return this; }
    public CreateWorkflowRequestDto statuses(List<CreateWorkflowStatusRequestDto> value) { setStatuses(value); return this; }
    public CreateWorkflowRequestDto transitions(List<CreateWorkflowTransitionRequestDto> value) { setTransitions(value); return this; }
    public CreateWorkflowRequestDto statusMappings(Map<String, String> value) { setStatusMappings(value); return this; }

    @JsonAnySetter
    public void rejectUnknownField(String key, Object value) { throw new IllegalArgumentException("Unknown field: " + key); }
}
