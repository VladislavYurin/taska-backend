package ru.taska.mapper;

import org.mapstruct.Mapper;
import ru.taska.api.project.v1.ProjectShortInfo;
import ru.taska.domain.dto.ProjectInfo;

import java.util.Map;

@Mapper(componentModel = "spring")
public interface ProjectClientMapper {
    Map<String, ProjectInfo> toDomainMap(Map<String, ProjectShortInfo> protoMap);

    default ProjectInfo toDomain(ProjectShortInfo proto) {
        if (proto == null) return null;
        return new ProjectInfo(proto.getId(), proto.getKey(), proto.getName(), proto.getIssueTypesList().stream()
                .collect(java.util.stream.Collectors.toMap(ru.taska.api.project.v1.ProjectIssueTypeResponse::getCode,
                        type -> new ru.taska.domain.dto.IssueTypeMetadata(type.getName(), type.getColor(), type.getIcon()))));
    }
}
