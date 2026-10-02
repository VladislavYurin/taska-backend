package ru.taska.transport.grpc;

import io.grpc.StatusRuntimeException;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.common.v1.GlobalRoleProto;
import ru.taska.api.common.v1.Header;
import ru.taska.api.project.v1.GetProjectContextRequest;
import ru.taska.api.project.v1.GetProjectContextRequestBody;
import ru.taska.api.project.v1.ProjectContextResponse;
import ru.taska.domain.GlobalRole;
import ru.taska.domain.dto.projectContext.ProjectContextDto;
import ru.taska.exception.DomainException;
import ru.taska.exception.DomainStatus;
import ru.taska.mapper.ProjectMapper;
import ru.taska.mapper.ProjectMemberMapper;
import ru.taska.service.ProjectMemberService;
import ru.taska.service.ProjectService;

import java.util.UUID;

@ExtendWith(MockitoExtension.class)
class GrpcProjectServiceTest {

    @Mock
    private ProjectService projectService;

    @Mock
    private ProjectMemberService projectMemberService;

    @Mock
    private ProjectMapper projectMapper;

    @Mock
    private ProjectMemberMapper projectMemberMapper;

    @InjectMocks
    private GrpcProjectService grpcProjectService;

    private static final String REQUEST_ID = "req-ctx-1";
    private static final String NODE_ID = "api-gateway";
    private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID ACTOR_ID   = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    private GetProjectContextRequest validRequest(GlobalRoleProto globalRole) {
        return GetProjectContextRequest.newBuilder()
                .setHeader(Header.newBuilder()
                        .setRequestId(REQUEST_ID)
                        .setNodeId(NODE_ID)
                        .build())
                .setBody(GetProjectContextRequestBody.newBuilder()
                        .setProjectId(PROJECT_ID.toString())
                        .setActorUserId(ACTOR_ID.toString())
                        .setGlobalRole(globalRole)
                        .build())
                .build();
    }

    @Nested
    @DisplayName("getProjectContext")
    class GetProjectContextTests {

        private ProjectContextDto domainDto;
        private ProjectContextResponse protoResponse;

        @BeforeEach
        void setUpData() {
            domainDto = Mockito.mock(ProjectContextDto.class);
            protoResponse = ProjectContextResponse.getDefaultInstance();

            Mockito.lenient().when(projectMapper.toGlobalRole(ArgumentMatchers.any(GlobalRoleProto.class)))
                    .thenReturn(GlobalRole.USER);
        }

        @Test
        @DisplayName("happy path: валидный запрос вызывает сервис с распарсенными аргументами и возвращает proto")
        void happyPath_callsServiceAndReturnsProto() {
            Mockito.when(projectMapper.toGlobalRole(GlobalRoleProto.GLOBAL_ROLE_GLOBAL_ADMIN))
                    .thenReturn(GlobalRole.GLOBAL_ADMIN);
            Mockito.when(projectService.getProjectContext(
                            REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_ID, GlobalRole.GLOBAL_ADMIN))
                    .thenReturn(Mono.just(domainDto));
            Mockito.when(projectMapper.toProjectContextResponse(domainDto))
                    .thenReturn(protoResponse);

            StepVerifier.create(grpcProjectService.getProjectContext(
                            Mono.just(validRequest(GlobalRoleProto.GLOBAL_ROLE_GLOBAL_ADMIN))))
                    .expectNext(protoResponse)
                    .verifyComplete();

            // проверяем, что сервис получил именно распарсенные значения и правильную роль
            Mockito.verify(projectService).getProjectContext(
                    REQUEST_ID, NODE_ID, PROJECT_ID, ACTOR_ID, GlobalRole.GLOBAL_ADMIN);
            Mockito.verify(projectMapper).toProjectContextResponse(domainDto);
        }

        @Test
        @DisplayName("PERMISSION_DENIED от сервиса → пробрасывается наружу")
        void permissionDenied_propagates() {
            Mockito.when(projectMapper.toGlobalRole(ArgumentMatchers.any(GlobalRoleProto.class)))
                    .thenReturn(GlobalRole.USER);
            Mockito.when(projectService.getProjectContext(
                            ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
                            ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                    .thenReturn(Mono.error(new DomainException(
                            DomainStatus.PERMISSION_DENIED, "Access denied")));

            StepVerifier.create(grpcProjectService.getProjectContext(
                            Mono.just(validRequest(GlobalRoleProto.GLOBAL_ROLE_USER))))
                    .expectErrorSatisfies(throwable -> {
                        Assertions.assertThat(throwable).isInstanceOf(DomainException.class);
                        DomainException e = (DomainException) throwable;
                        Assertions.assertThat(e.getStatus()).isEqualTo(DomainStatus.PERMISSION_DENIED);
                    })
                    .verify();

            Mockito.verify(projectMapper, Mockito.never()).toProjectContextResponse(ArgumentMatchers.any());
        }

        @Test
        @DisplayName("NOT_FOUND от сервиса → пробрасывается наружу")
        void notFound_propagates() {
            Mockito.when(projectMapper.toGlobalRole(ArgumentMatchers.any(GlobalRoleProto.class)))
                    .thenReturn(GlobalRole.USER);
            Mockito.when(projectService.getProjectContext(
                            ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
                            ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                    .thenReturn(Mono.error(new DomainException(
                            DomainStatus.NOT_FOUND, "Project not found")));

            StepVerifier.create(grpcProjectService.getProjectContext(
                            Mono.just(validRequest(GlobalRoleProto.GLOBAL_ROLE_USER))))
                    .expectErrorSatisfies(throwable -> {
                        DomainException e = (DomainException) throwable;
                        Assertions.assertThat(e.getStatus()).isEqualTo(DomainStatus.NOT_FOUND);
                    })
                    .verify();
        }

        @Test
        @DisplayName("пустой requestId → INVALID_ARGUMENT, сервис не вызывается")
        void blankRequestId_failsValidation() {
            var bad = GetProjectContextRequest.newBuilder()
                    .setHeader(Header.newBuilder()
                            .setRequestId("")     // ← пусто
                            .setNodeId(NODE_ID)
                            .build())
                    .setBody(GetProjectContextRequestBody.newBuilder()
                            .setProjectId(PROJECT_ID.toString())
                            .setActorUserId(ACTOR_ID.toString())
                            .setGlobalRole(GlobalRoleProto.GLOBAL_ROLE_USER)
                            .build())
                    .build();

            StepVerifier.create(grpcProjectService.getProjectContext(Mono.just(bad)))
                    .expectError(StatusRuntimeException.class)
                    .verify();

            Mockito.verifyNoInteractions(projectService);
        }

        @Test
        @DisplayName("невалидный projectId → INVALID_ARGUMENT, сервис не вызывается")
        void invalidProjectId_failsValidation() {
            var bad = GetProjectContextRequest.newBuilder()
                    .setHeader(Header.newBuilder()
                            .setRequestId(REQUEST_ID)
                            .setNodeId(NODE_ID)
                            .build())
                    .setBody(GetProjectContextRequestBody.newBuilder()
                            .setProjectId("not-a-uuid")
                            .setActorUserId(ACTOR_ID.toString())
                            .setGlobalRole(GlobalRoleProto.GLOBAL_ROLE_USER)
                            .build())
                    .build();

            StepVerifier.create(grpcProjectService.getProjectContext(Mono.just(bad)))
                    .expectError(StatusRuntimeException.class)
                    .verify();

            Mockito.verifyNoInteractions(projectService);
        }

        @Test
        @DisplayName("невалидный actorUserId → INVALID_ARGUMENT, сервис не вызывается")
        void invalidActorUserId_failsValidation() {
            var bad = GetProjectContextRequest.newBuilder()
                    .setHeader(Header.newBuilder()
                            .setRequestId(REQUEST_ID)
                            .setNodeId(NODE_ID)
                            .build())
                    .setBody(GetProjectContextRequestBody.newBuilder()
                            .setProjectId(PROJECT_ID.toString())
                            .setActorUserId("bad-uuid")
                            .setGlobalRole(GlobalRoleProto.GLOBAL_ROLE_USER)
                            .build())
                    .build();

            StepVerifier.create(grpcProjectService.getProjectContext(Mono.just(bad)))
                    .expectError(StatusRuntimeException.class)
                    .verify();

            Mockito.verifyNoInteractions(projectService);
        }

        @Test
        @DisplayName("пустой nodeId → INVALID_ARGUMENT, сервис не вызывается")
        void blankNodeId_failsValidation() {
            var bad = GetProjectContextRequest.newBuilder()
                    .setHeader(Header.newBuilder()
                            .setRequestId(REQUEST_ID)
                            .setNodeId("")
                            .build())
                    .setBody(GetProjectContextRequestBody.newBuilder()
                            .setProjectId(PROJECT_ID.toString())
                            .setActorUserId(ACTOR_ID.toString())
                            .setGlobalRole(GlobalRoleProto.GLOBAL_ROLE_USER)
                            .build())
                    .build();

            StepVerifier.create(grpcProjectService.getProjectContext(Mono.just(bad)))
                    .expectError(StatusRuntimeException.class)
                    .verify();

            Mockito.verifyNoInteractions(projectService);
        }
    }
}