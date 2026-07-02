package ru.taska.dto;

import lombok.Builder;
import lombok.Data;
import ru.taska.api.workflow.v1.IssueStatus;
import ru.taska.api.workflow.v1.TransitionViolation;

import java.util.List;

@Data
@Builder
public class ValidateTransitionResponseDto {
    boolean valid;
    IssueStatus toStatusKey;
    List<TransitionViolationDto> violations;
}
