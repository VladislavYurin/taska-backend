package ru.taska.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.auth.profile.v1.*;
import ru.taska.domain.ProjectRole;
import ru.taska.domain.dto.ProjectMemberStatsRow;
import ru.taska.repository.ProjectMemberRepository;
import ru.taska.service.impl.ProjectMemberServiceImpl;
import ru.taska.transport.grpc.client.GrpcAuthServiceClient;

class ProjectMemberStatsTest {
    private final ProjectMemberRepository repository = mock(ProjectMemberRepository.class);
    private final GrpcAuthServiceClient auth = mock(GrpcAuthServiceClient.class);
    private final ProjectMemberServiceImpl service = new ProjectMemberServiceImpl(repository, null, null, null, auth, null);

    @Test
    void allProjectsShareOneProfileRequestAndMissingProfilesKeepMembership() {
        UUID actor = UUID.randomUUID(), first = UUID.randomUUID(), second = UUID.randomUUID();
        UUID known = UUID.randomUUID(), missing = UUID.randomUUID();
        var time = Instant.parse("2026-10-01T00:00:00Z");
        when(repository.findMemberStatsBatch(List.of(first, second), actor)).thenReturn(Flux.just(
                new ProjectMemberStatsRow(first, known, ProjectRole.ADMIN, time, 7),
                new ProjectMemberStatsRow(first, missing, ProjectRole.VIEWER, time, 7),
                new ProjectMemberStatsRow(second, known, ProjectRole.MEMBER, time, 1)));
        when(auth.getUserDetailsByIds(List.of(known, missing), "req", "node")).thenReturn(Mono.just(
                GetUserDetailsByIdsResponse.newBuilder().addUserDetails(UserDetails.newBuilder()
                        .setUserId(known.toString()).setDisplayName("Known").setEmail("known@test.dev")).build()));
        StepVerifier.create(service.getMemberStatsBatch(List.of(first, second), actor, "req", "node").collectList())
                .assertNext(stats -> {
                    assertThat(stats).hasSize(2);
                    assertThat(stats.getFirst().memberCount()).isEqualTo(7);
                    assertThat(stats.getFirst().members()).hasSize(2);
                    assertThat(stats.getFirst().members().getFirst().avatar()).isNull();
                    assertThat(stats.getFirst().members().get(1).userId()).isEqualTo(missing);
                    assertThat(stats.getFirst().members().get(1).role()).isEqualTo(ProjectRole.VIEWER);
                    assertThat(stats.getFirst().members().get(1).displayName()).isNull();
                }).verifyComplete();
        verify(auth).getUserDetailsByIds(List.of(known, missing), "req", "node");
    }

    @Test
    void emptyIdsNeverQueryDatabaseOrAuth() {
        StepVerifier.create(service.getMemberStatsBatch(List.of(), UUID.randomUUID(), "req", "node")).verifyComplete();
        verifyNoInteractions(repository, auth);
    }

    @Test
    void inaccessibleProjectsNeverQueryAuth() {
        UUID project = UUID.randomUUID(), actor = UUID.randomUUID();
        when(repository.findMemberStatsBatch(List.of(project), actor)).thenReturn(Flux.empty());
        StepVerifier.create(service.getMemberStatsBatch(List.of(project), actor, "req", "node")).verifyComplete();
        verifyNoInteractions(auth);
    }
}
