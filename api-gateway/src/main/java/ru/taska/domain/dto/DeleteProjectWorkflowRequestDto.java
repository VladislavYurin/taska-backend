package ru.taska.domain.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.Nulls;
import java.math.BigDecimal;
import java.util.Map;
import lombok.Getter;

@Getter
public class DeleteProjectWorkflowRequestDto implements WorkflowPatchBody {
    private Integer version;
    private Map<String, String> statusMappings = Map.of();

    @JsonSetter(nulls = Nulls.FAIL)
    public void setVersion(BigDecimal value) {
        this.version = value.intValueExact();
    }

    @JsonSetter(nulls = Nulls.FAIL)
    public void setStatusMappings(Map<String, String> value) {
        if (value.values().stream().anyMatch(java.util.Objects::isNull)) throw new IllegalArgumentException("Null status mapping");
        this.statusMappings = value;
    }

    @JsonAnySetter
    public void rejectUnknownField(String key, Object value) {
        throw new IllegalArgumentException("Unknown or immutable field: " + key);
    }
}
