package ru.taska.domain.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.Nulls;
import java.math.BigDecimal;
import java.util.Map;
import lombok.Getter;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotBlank;

@Getter
public class PatchWorkflowRequestDto implements WorkflowPatchBody {
    @NotBlank
    @Size(min = 1, max = 100)
    private String name;
    @Min(1)
    private Integer version;

    @JsonSetter(nulls = Nulls.FAIL)
    public void setName(String value) {
        this.name = value;
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
