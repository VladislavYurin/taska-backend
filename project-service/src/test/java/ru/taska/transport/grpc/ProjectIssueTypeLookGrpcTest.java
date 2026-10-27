package ru.taska.transport.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.UUID;
import nullable.NullableField;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.project.v1.ChangeProjectIssueTypeRequest;
import ru.taska.domain.ProjectIssueType;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.mapper.ProjectMapperImpl;
import ru.taska.service.ProjectIssueTypeService;

/** TAS-432: how shape and the optional icon cross gRPC in both directions. */
class ProjectIssueTypeLookGrpcTest {
    private final UUID project = UUID.randomUUID(), actor = UUID.randomUUID();
    private final ProjectIssueTypeService service = mock(ProjectIssueTypeService.class);
    private final ru.taska.service.IssueTypeCreationCoordinator creation = mock(ru.taska.service.IssueTypeCreationCoordinator.class);
    private final ProjectIssueTypeGrpcService grpc = new ProjectIssueTypeGrpcService(service, new ProjectMapperImpl(),
            mock(ru.taska.service.IssueTypeDeletionCoordinator.class), creation);

    @Test
    void clearIconBecomesExplicitNullAndUnsetIconStaysAbsent() {
        when(service.update(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(type("REQUEST", null, "diamond")));
        grpc.update(Mono.just(request().setClearIcon(true).setShape("diamond").build())).block();
        verify(service).update(project, actor, "REQUEST", null, null, NullableField.of(null), "diamond", null);
        grpc.update(Mono.just(request().setName("Request").build())).block();
        verify(service).update(project, actor, "REQUEST", "Request", null, NullableField.absent(), null, null);
        grpc.update(Mono.just(request().setIcon("flag").build())).block();
        verify(service).update(project, actor, "REQUEST", null, null, NullableField.of("flag"), null, null);
    }

    @Test
    void iconTogetherWithClearIconIsRefused() {
        StepVerifier.create(grpc.update(Mono.just(request().setIcon("flag").setClearIcon(true).build())))
                .expectErrorSatisfies(error -> assertThat(((DomainException) error).getStatus())
                        .isEqualTo(DomainStatus.INVALID_ARGUMENT)).verify();
        verifyNoInteractions(service);
    }

    @Test
    void createPassesOptionalShapeAndIcon() {
        when(creation.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(new ru.taska.service.IssueTypeCreationTransactions.Created(
                        type("REQUEST", null, "square"), UUID.randomUUID())));
        grpc.create(Mono.just(request().setName("Request").setColor("teal").build())).block();
        verify(creation).create(project, actor, "REQUEST", "Request", "teal", null, null, null, null,
                ru.taska.api.common.v1.Header.getDefaultInstance());
        grpc.create(Mono.just(request().setName("Request").setColor("teal").setIcon("flag").setShape("circle").build())).block();
        verify(creation).create(project, actor, "REQUEST", "Request", "teal", "flag", "circle", null, null,
                ru.taska.api.common.v1.Header.getDefaultInstance());
        verifyNoInteractions(service);
    }

    @Test
    void responseCarriesLookForNewReadersAndAValidLegacyIconForOldOnes() {
        when(service.update(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Mono.just(type("BUG", null, "circle")))
                .thenReturn(Mono.just(type("REQUEST", null, "diamond")))
                .thenReturn(Mono.just(type("REQUEST", "headset", "square")));
        var plainBug = grpc.update(Mono.just(request().setShape("circle").build())).block();
        assertThat(plainBug.getShape()).isEqualTo("circle");
        assertThat(plainBug.hasIcon()).isFalse();
        assertThat(plainBug.getLegacyIcon()).isEqualTo("bug");
        var plainCustom = grpc.update(Mono.just(request().setShape("diamond").build())).block();
        assertThat(plainCustom.hasIcon()).isFalse();
        assertThat(plainCustom.getLegacyIcon()).isEqualTo("check-square");
        var withIcon = grpc.update(Mono.just(request().setIcon("headset").build())).block();
        assertThat(withIcon.getIcon()).isEqualTo("headset");
        assertThat(withIcon.getLegacyIcon()).isEqualTo("headset");
    }

    private ChangeProjectIssueTypeRequest.Builder request() {
        return ChangeProjectIssueTypeRequest.newBuilder().setProjectId(project.toString())
                .setActorUserId(actor.toString()).setCode("REQUEST");
    }

    private ProjectIssueType type(String code, String icon, String shape) {
        return new ProjectIssueType(project, code, "Name", "blue", icon, shape, 10, null, null, UUID.randomUUID());
    }
}
