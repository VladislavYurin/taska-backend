package ru.taska.dto;

import lombok.Data;
import lombok.RequiredArgsConstructor;

@Data
@RequiredArgsConstructor
public class TransitionViolationDto {
    private final Violation violation;
    private final String message;

}
