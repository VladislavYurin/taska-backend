package ru.taska.workflowstorage;

import java.util.UUID;
import reactor.core.publisher.Mono;
import ru.taska.api.project.v1.CheckProjectMemberRoleResponse;
import ru.taska.api.project.v1.ListProjectIssueTypesResponse;

/** Current project authority; implementations must not cache actor decisions. */
public interface WorkflowProjectAccess {
  Mono<CheckProjectMemberRoleResponse> check(UUID project);

  Mono<ListProjectIssueTypesResponse> types(UUID project);
}
