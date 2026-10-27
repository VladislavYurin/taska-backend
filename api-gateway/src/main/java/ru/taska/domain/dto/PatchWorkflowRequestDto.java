package ru.taska.domain.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import lombok.Getter;

@Getter
public class PatchWorkflowRequestDto implements WorkflowPatchBody {
    @Size(min = 1, max = 100) private String name;
    @Min(1) private Integer version;
    private List<String> issueTypes;
    private Map<String, String> statusMappings = Map.of();

    @JsonSetter(nulls = Nulls.FAIL)
    public void setName(String value) {
        if (value.isBlank()) throw new IllegalArgumentException("name must not be blank");
        name = value;
    }
    @JsonSetter(nulls = Nulls.FAIL)
    public void setVersion(BigDecimal value) { version = value.intValueExact(); }
    @JsonSetter(nulls = Nulls.FAIL)
    public void setIssueTypes(List<String> value) { issueTypes = WorkflowCommandValidation.issueTypes(value); }
    @JsonSetter(nulls = Nulls.FAIL)
    public void setStatusMappings(Map<String, String> value) { statusMappings = WorkflowCommandValidation.statusMappings(value); }

    @JsonIgnore
    @AssertTrue(message = "Specify name or issueTypes; binding changes require version")
    public boolean isValidCommand() {
        return (name != null || issueTypes != null) && (issueTypes == null || version != null)
                && (statusMappings.isEmpty() || issueTypes != null);
    }

    @JsonAnySetter
    public void rejectUnknownField(String key, Object value) { throw new IllegalArgumentException("Unknown or immutable field: " + key); }
}
