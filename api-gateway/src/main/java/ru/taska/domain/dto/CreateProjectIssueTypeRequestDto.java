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
import lombok.Getter;
import lombok.Setter;

@Getter
public class CreateProjectIssueTypeRequestDto {
    @Setter @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,31}$") private String code;
    @Setter @NotBlank @Size(max = 50) private String name;
    @Setter @NotNull private ProjectIssueTypeColorDto color;
    @Setter @NotNull private ProjectIssueTypeIconDto icon;
    @PositiveOrZero private Integer sortOrder = 100;

    @JsonSetter(nulls = Nulls.FAIL)
    public void setSortOrder(BigDecimal value) { sortOrder = value.intValueExact(); }

    @JsonAnySetter
    public void rejectUnknownField(String key, Object value) {
        throw new IllegalArgumentException("Unknown field: " + key);
    }
}
