package ru.taska.domain.dto;

import java.time.LocalDate;

public record CreateWorklogDto(
        Integer spentMinutes,
        LocalDate workDate,
        String comment
) {
}
