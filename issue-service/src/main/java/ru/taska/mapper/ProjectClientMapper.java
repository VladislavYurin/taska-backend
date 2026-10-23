package ru.taska.mapper;

import org.mapstruct.Mapper;
import ru.taska.api.project.v1.ProjectShortInfo;
import ru.taska.domain.dto.ProjectInfo;

import java.util.Map;

@Mapper(componentModel = "spring")
public interface ProjectClientMapper {
    Map<String, ProjectInfo> toDomainMap(Map<String, ProjectShortInfo> protoMap);

    ProjectInfo toDomain(ProjectShortInfo proto);
}
