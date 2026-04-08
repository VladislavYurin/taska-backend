package ru.taska.domain;

import ru.taska.entity.StatusEntity;
import ru.taska.entity.TransitionEntity;
import ru.taska.entity.WorkflowEntity;

import java.util.List;

public record WorkflowAggregate(
        WorkflowEntity workflow,
        List<StatusEntity> statuses,
        List<TransitionEntity> transitions
) {
}