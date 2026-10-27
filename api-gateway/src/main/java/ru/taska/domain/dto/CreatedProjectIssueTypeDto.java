package ru.taska.domain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.Getter;

/** Creation-only response; the shared project registry representation is unchanged. */
@Getter
public class CreatedProjectIssueTypeDto extends ProjectIssueTypeDto {
    @NotNull
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private final UUID workflowId;

    public CreatedProjectIssueTypeDto(ProjectIssueTypeDto type, UUID workflowId) {
        super(type.getCode(), type.getName(), type.getColor(), type.getShape(), type.getIcon(), type.getSortOrder());
        setGeneration(type.getGeneration());
        this.workflowId = workflowId;
    }
}
