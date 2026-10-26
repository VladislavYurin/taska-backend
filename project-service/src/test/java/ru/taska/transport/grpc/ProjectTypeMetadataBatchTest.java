package ru.taska.transport.grpc;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.taska.api.common.v1.Header;
import ru.taska.api.project.v1.*;
import ru.taska.domain.ProjectIssueType;
import ru.taska.domain.projection.ProjectInfo;
import ru.taska.mapper.ProjectMapperImpl;
import ru.taska.repository.ProjectIssueTypeRepository;
import ru.taska.service.ProjectService;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ProjectTypeMetadataBatchTest {
    @Test
    void loadsMetadataForAllRequestedProjectsWithOneRepositoryBatch() {
        var one = UUID.randomUUID(); var two = UUID.randomUUID();
        var projects = mock(ProjectService.class); var types = mock(ProjectIssueTypeRepository.class);
        when(projects.getProjectInfoByIds(List.of(one, two))).thenReturn(Mono.just(Map.of(
                one, new ProjectInfo(one, "ONE", "One"), two, new ProjectInfo(two, "TWO", "Two"))));
        when(types.listBatch(List.of(one, two))).thenReturn(Flux.just(
                new ProjectIssueType(one, "REQUEST", "Заявка", "teal", "headset", 10, null, null, UUID.randomUUID()),
                new ProjectIssueType(two, "REQUEST", "Запрос", "blue", "flag", 10, null, null, UUID.randomUUID())));
        var service = new GrpcProjectService(projects, null, new ProjectMapperImpl(), null, types);
        var response = service.getProjectInfoBatchInternal(Mono.just(GetProjectInfoBatchInternalRequest.newBuilder()
                .setHeader(Header.newBuilder().setRequestId("r").setNodeId("n"))
                .setBody(GetProjectInfoBatchInternalRequestBody.newBuilder().addProjectIds(one.toString()).addProjectIds(two.toString()))
                .build())).block();
        assertThat(response.getProjectsOrThrow(one.toString()).getIssueTypes(0).getName()).isEqualTo("Заявка");
        assertThat(response.getProjectsOrThrow(two.toString()).getIssueTypes(0).getName()).isEqualTo("Запрос");
        verify(types).listBatch(List.of(one, two));
        verifyNoMoreInteractions(types);
    }
}
