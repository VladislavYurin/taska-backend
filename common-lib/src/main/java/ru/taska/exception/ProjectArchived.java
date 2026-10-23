package ru.taska.exception;

import java.util.Map;

public final class ProjectArchived {
    private ProjectArchived() { }

    public static DomainException error() {
        return new DomainException(DomainStatus.FAILED_PRECONDITION, "Project is archived",
                Map.of("reason", "PROJECT_ARCHIVED"));
    }
}
