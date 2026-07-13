package ru.taska.mapper;

import org.springframework.stereotype.Component;
import ru.taska.domain.ProjectRole;

@Component
public class RoleMapper {

    public ProjectRole toDomainRole(ru.taska.api.project.v1.ProjectRole protoRole) {
        return switch (protoRole) {
            case PROJECT_ROLE_ADMIN -> ProjectRole.ADMIN;
            case PROJECT_ROLE_MEMBER -> ProjectRole.MEMBER;
            case PROJECT_ROLE_VIEWER -> ProjectRole.VIEWER;
            default -> throw new IllegalArgumentException("Unknown ProjectRole: " + protoRole);
        };
    }
}
