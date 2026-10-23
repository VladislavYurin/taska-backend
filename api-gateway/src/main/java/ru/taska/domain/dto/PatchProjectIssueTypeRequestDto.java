package ru.taska.domain.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.Nulls;
import java.math.BigDecimal;
import lombok.Getter;

/** Optional fields are mutable; explicit null is rejected because these values cannot be cleared. */
@Getter
public class PatchProjectIssueTypeRequestDto {
    private String name;
    private String color;
    private String icon;
    private Integer sortOrder;

    @JsonSetter(nulls = Nulls.FAIL)
    public void setName(String name) { this.name = name; }

    @JsonSetter(nulls = Nulls.FAIL)
    public void setColor(String color) { this.color = color; }

    @JsonSetter(nulls = Nulls.FAIL)
    public void setIcon(String icon) { this.icon = icon; }

    @JsonSetter(nulls = Nulls.FAIL)
    public void setSortOrder(BigDecimal sortOrder) { this.sortOrder = sortOrder.intValueExact(); }

    @JsonAnySetter
    public void rejectUnknownField(String key, Object value) {
        throw new IllegalArgumentException("Unknown or immutable field: " + key);
    }
}
