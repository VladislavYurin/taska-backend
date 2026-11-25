package ru.taska.workflowbootstrap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.*;
import ru.taska.workflowstorage.ProjectWorkflowBootstrap;

@Configuration(proxyBeanMethods = false)
@Profile("tenant-storage")
@ConditionalOnExpression(
    "${taska.rpc.context.enabled:false} && '${taska.rpc.trust.snapshot:}' != ''")
@Import(ProjectWorkflowBootstrap.class)
public class ProjectInitializationConfiguration {}
