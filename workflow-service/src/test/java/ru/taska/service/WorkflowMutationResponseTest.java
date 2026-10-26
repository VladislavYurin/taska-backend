package ru.taska.service;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.common.v1.Header;
import ru.taska.api.workflow.v1.WorkflowResponse;
import ru.taska.entity.WorkflowBindingEntity;
import ru.taska.mapper.WorkflowMapper;
import ru.taska.repository.TransitionRepository;
import ru.taska.repository.WorkflowBindingRepository;
import ru.taska.transport.grpc.project.GrpcProjectServiceClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class WorkflowMutationResponseTest {
    @Test
    void mutationIssueTypesFollowTheSameProjectRegistryOrderAsListing() {
        var bindings = mock(WorkflowBindingRepository.class);
        var transitions = mock(TransitionRepository.class);
        var projects = mock(GrpcProjectServiceClient.class);
        var service = new WorkflowMutationResponse(bindings, transitions, new WorkflowMapper(), projects);
        UUID workflow = UUID.randomUUID(), project = UUID.randomUUID(), actor = UUID.randomUUID();
        UUID requestGeneration = UUID.randomUUID();
        var registryOrder = List.of("BUG", "REQUEST", "RECREATED", "TASK", "STORY").stream().map(key ->
                ru.taska.api.project.v1.ProjectIssueTypeResponse.newBuilder().setCode(key)
                        .setGeneration((key.equals("RECREATED") ? UUID.randomUUID()
                                : key.equals("REQUEST") ? requestGeneration : IssueTypeBindingIdentity.initialGeneration(project, key)).toString())
                        .build()).toList();
        when(projects.listIssueTypeDetails("r", "n", project, actor)).thenReturn(Mono.just(registryOrder));
        when(bindings.findByWorkflowId(workflow)).thenReturn(Flux.fromIterable(
                List.of("STORY", "TASK", "REMOVED", "REQUEST", "BUG", "RECREATED").stream().map(key ->
                        WorkflowBindingEntity.builder().issueType(key).projectId(project).workflowId(workflow)
                                .issueTypeGeneration(key.equals("REQUEST") ? requestGeneration : null).build()).toList()));
        when(transitions.findByWorkflowIdOrderBySortOrderAsc(workflow)).thenReturn(Flux.empty());
        StepVerifier.create(service.issueTypes(Header.newBuilder().setRequestId("r").setNodeId("n").build(),
                        project.toString(), actor.toString()).flatMap(types -> service.enrich(WorkflowResponse.newBuilder()
                        .setId(workflow.toString()).addIssueTypeKeys("STALE").build(), types)))
                .assertNext(response -> {
                    assertThat(response.getIssueTypeKeysList()).containsExactly("BUG", "REQUEST", "TASK", "STORY");
                    assertThat(response.getIssueTypesList()).containsExactly(
                            ru.taska.api.workflow.v1.IssueType.ISSUE_TYPE_BUG,
                            ru.taska.api.workflow.v1.IssueType.ISSUE_TYPE_UNSPECIFIED,
                            ru.taska.api.workflow.v1.IssueType.ISSUE_TYPE_TASK,
                            ru.taska.api.workflow.v1.IssueType.ISSUE_TYPE_STORY);
                }).verifyComplete();
    }
    @Test
    void oldRegistryWithoutGenerationFailsBeforeAnyMutation() {
        var bindings = mock(WorkflowBindingRepository.class);
        var transitions = mock(TransitionRepository.class);
        var projects = mock(GrpcProjectServiceClient.class);
        var service = new WorkflowMutationResponse(bindings, transitions, new WorkflowMapper(), projects);
        UUID project = UUID.randomUUID(), actor = UUID.randomUUID();
        when(projects.listIssueTypeDetails("r", "n", project, actor)).thenReturn(Mono.just(List.of(
                ru.taska.api.project.v1.ProjectIssueTypeResponse.newBuilder().setCode("TASK").build())));
        StepVerifier.create(service.issueTypes(Header.newBuilder().setRequestId("r").setNodeId("n").build(),
                        project.toString(), actor.toString()))
                .expectErrorMatches(error -> error instanceof ru.taska.exception.DomainException domain
                        && domain.getStatus() == ru.taska.exception.DomainStatus.UNAVAILABLE).verify();
        verifyNoInteractions(bindings, transitions);
    }

}
