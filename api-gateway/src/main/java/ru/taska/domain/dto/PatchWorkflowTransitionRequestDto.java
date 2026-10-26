package ru.taska.domain.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.Nulls;
import java.math.BigDecimal;
import java.util.Map;
import lombok.Getter;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.PositiveOrZero;

@Getter
public class PatchWorkflowTransitionRequestDto implements WorkflowPatchBody {
    @Size(min = 1, max = 100)
    private String name;
    private Boolean hidden;
    @PositiveOrZero
    private Integer sortOrder;
    @Min(1)
    private Integer version;

    @JsonSetter(nulls = Nulls.FAIL)
    public void setName(String value) {
        this.name = value;
    }

    @JsonSetter(nulls = Nulls.FAIL)
    public void setHidden(Boolean value) {
        this.hidden = value;
    }

    @JsonSetter(nulls = Nulls.FAIL)
    public void setSortOrder(BigDecimal value) {
        this.sortOrder = value.intValueExact();
    }

    @JsonSetter(nulls = Nulls.FAIL)
    public void setVersion(BigDecimal value) {
        this.version = value.intValueExact();
    }

    @JsonAnySetter
    public void rejectUnknownField(String key, Object value) {
        throw new IllegalArgumentException("Unknown or immutable field: " + key);
    }
}
