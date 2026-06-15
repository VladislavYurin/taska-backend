package ru.taska.mapper;

import com.google.protobuf.Timestamp;
import org.mapstruct.*;
import org.mapstruct.CollectionMappingStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.NullValueCheckStrategy;
import org.mapstruct.ReportingPolicy;
import ru.taska.api.project.v1.ProjectResponse;
import ru.taska.domain.Project;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING,
        collectionMappingStrategy = CollectionMappingStrategy.ADDER_PREFERRED,
        unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface ProjectMapper {
    /**
     * Маппинг {@link Project} в {@link ProjectResponse}
     * param {@link Project}
     * return {@link ProjectResponse}
     */
    @Mapping(target = "id", source = "id")
    @Mapping(target = "projectKey", source = "projectKey")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "createdBy", source = "createdBy")
    @Mapping(target = "createdAt", source = "createdAt")
    @Mapping(target = "updatedAt", source = "updatedAt")
    @Mapping(target = "archivedAt", source = "archivedAt", nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS)
    ProjectResponse toProjectResponse(Project project);

    List<ProjectResponse> toProjectResponseList(List<Project> projects);

    /**
     * Конвертация UUID в String
     */
    default String mapUuidToString(UUID uuid) {
        return uuid != null ? uuid.toString() : null;
    }

    /**
     * Конвертация Java Instant в Protobuf Timestamp
     */
    default Timestamp mapInstantToTimestamp(Instant instant) {
        if (instant == null) {
            return Timestamp.getDefaultInstance();
        }
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }
}