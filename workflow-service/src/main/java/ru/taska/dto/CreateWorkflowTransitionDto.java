package ru.taska.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CreateWorkflowTransitionDto {
    private String fromStatusKey;
    private String toStatusKey;
    private String name;
    private int sortOrder;
    private boolean hidden;
}
