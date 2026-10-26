package ru.taska;

import ru.taska.api.project.v1.ProjectIssueTypeResponse;
import ru.taska.domain.IssueType;
import ru.taska.service.IssueTypeBindingIdentity;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public final class RegistryFixtures {
    public static List<ProjectIssueTypeResponse> types(UUID project, IssueType... codes) {
        return Arrays.stream(codes).map(type -> ProjectIssueTypeResponse.newBuilder().setCode(type.name())
                .setName(type.name()).setColor("blue").setIcon("bug")
                .setGeneration(IssueTypeBindingIdentity.initialGeneration(project, type.name()).toString()).build()).toList();
    }
}
