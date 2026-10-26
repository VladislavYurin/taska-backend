package ru.taska.domain.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Keep duplicates until the registry validates the complete permutation; a Set would erase invalid input. */
@Data
@NoArgsConstructor
public class ReorderProjectIssueTypesRequestDto {
    @NotNull
    @Size(min = 1)
    private List<@NotNull @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,31}$") String> codes;
}
