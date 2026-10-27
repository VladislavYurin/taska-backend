package ru.taska.domain.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** Absent or null icon creates a type without one; absent shape is left to the registry default (square). */
@Getter
public class CreateProjectIssueTypeRequestDto {
    @Setter @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,31}$") private String code;
    @Setter @NotBlank @Size(max = 50) private String name;
    @Setter @NotNull private ProjectIssueTypeColorDto color;
    private ProjectIssueTypeShapeDto shape;
    @Setter private ProjectIssueTypeIconDto icon;
    @PositiveOrZero private Integer sortOrder = 100;
    private UUID workflowId;

    @JsonSetter(nulls = Nulls.FAIL)
    public void setWorkflowId(String value) {
        if (value == null || !value.matches("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"))
            throw new IllegalArgumentException("workflowId must be a UUID");
        workflowId = UUID.fromString(value);
    }

    @JsonSetter(nulls = Nulls.FAIL)
    public void setShape(ProjectIssueTypeShapeDto shape) { this.shape = shape; }

    @JsonSetter(nulls = Nulls.FAIL)
    public void setSortOrder(BigDecimal value) { sortOrder = value.intValueExact(); }

    @JsonAnySetter
    public void rejectUnknownField(String key, Object value) {
        throw new IllegalArgumentException("Unknown field: " + key);
    }
}
