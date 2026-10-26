package ru.taska.domain.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.Nulls;
import java.math.BigDecimal;
import java.util.Map;
import lombok.Getter;

@Getter
public class PatchWorkflowStatusRequestDto implements WorkflowPatchBody {
    private String name;
    private WorkflowStatusCategoryDto category;
    private Integer sortOrder;
    private Integer version;

    @JsonSetter(nulls = Nulls.FAIL)
    public void setName(String value) {
        this.name = value;
    }

    @JsonSetter(nulls = Nulls.FAIL)
    public void setCategory(WorkflowStatusCategoryDto value) {
        this.category = value;
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
