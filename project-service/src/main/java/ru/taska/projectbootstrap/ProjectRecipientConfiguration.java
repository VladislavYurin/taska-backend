package ru.taska.projectbootstrap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import ru.taska.projectstorage.ProjectRecipientRpc;

/** Новый internal RPC не использует legacy fallback и требует message-bound контекст. */
@Configuration(proxyBeanMethods = false)
@Profile("tenant-storage")
@ConditionalOnExpression(
    "${taska.rpc.context.enabled:false} && '${taska.rpc.trust.snapshot:}' != ''")
@Import(ProjectRecipientRpc.class)
public class ProjectRecipientConfiguration {}
