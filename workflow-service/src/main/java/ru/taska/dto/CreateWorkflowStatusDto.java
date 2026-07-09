package ru.taska.dto;

import lombok.Builder;
import lombok.Data;
import ru.taska.domain.StatusCategory;

@Data
@Builder
public class CreateWorkflowStatusDto {
    private String statusKey;
    private String name;
    private StatusCategory category;
    private int sortOrder;
}
