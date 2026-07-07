package ru.taska.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ValidateTransitionResponseDto {
    boolean valid;
    String toStatusKey;
    List<TransitionViolationDto> violations;
}
