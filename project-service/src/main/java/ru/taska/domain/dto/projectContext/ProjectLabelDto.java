package ru.taska.domain.dto.projectContext;

import lombok.Builder;

import java.util.UUID;

@Builder
public record ProjectLabelDto(
        UUID id,
        String name,
        String color
) {}
