package ru.taska.dto;

import lombok.Builder;

/**
 * DTO, которое будет содержать value в полях
 * @param equals - искомое значение должно быть равно value
 * @param contains - искомое значение должно содержать value
 * @param from - искомое значение должно быть >= value (для дат)
 * @param to - искомое значение должно быть <= value (для дат)
 * искомое значение - значение, которое будет сопоставляться с value, переданным в запросе
 */
@Builder
public record FilterOperatorsDto(
        String equals,
        String contains,
        String from,
        String to
) {}
