package ru.taska.service;

import java.util.List;
import java.util.UUID;
import ru.taska.entity.WorkflowBindingEntity;
import ru.taska.entity.WorkflowEntity;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;

/** Explicit owner remains authoritative even when no type uses the graph. */
final class WorkflowOwnership {
    private WorkflowOwnership() { }

    static void requireOwned(WorkflowEntity workflow, List<WorkflowBindingEntity> bindings,
                             UUID project, UUID defaultProject) {
        if (defaultProject.equals(workflow.getOwnerProjectId())
                || bindings.stream().anyMatch(b -> defaultProject.equals(b.getProjectId()))) {
            throw new DomainException(DomainStatus.FAILED_PRECONDITION,
                    "Default workflow is shared and cannot be modified");
        }
        boolean hasOwnBinding=bindings.stream().anyMatch(b -> project.equals(b.getProjectId()));
        boolean owned=workflow.getOwnerProjectId()==null ? hasOwnBinding
                : project.equals(workflow.getOwnerProjectId());
        if (!owned) throw new DomainException(DomainStatus.NOT_FOUND,"Workflow not found in project");
        if (bindings.stream().anyMatch(b -> !project.equals(b.getProjectId()))) {
            throw new DomainException(DomainStatus.FAILED_PRECONDITION,
                    "Workflow is shared with another project and cannot be modified");
        }

    }
}
