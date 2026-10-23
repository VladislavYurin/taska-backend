package ru.taska.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.NullValueCheckStrategy;
import org.mapstruct.NullValueMappingStrategy;
import ru.taska.api.common.v1.UserSummaryResponse;
import ru.taska.domain.dto.UserSummary;

@Mapper(
        componentModel = MappingConstants.ComponentModel.SPRING,
        nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS,
        nullValueMappingStrategy = NullValueMappingStrategy.RETURN_DEFAULT
)
public interface UserDetailsMapper {
    UserSummaryResponse toResponse(UserSummary userSummary);
}
