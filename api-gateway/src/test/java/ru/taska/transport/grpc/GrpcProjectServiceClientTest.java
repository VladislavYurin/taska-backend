package ru.taska.transport.grpc;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.taska.api.project.v1.AddProjectMemberRequest;
import ru.taska.api.project.v1.AddProjectMemberResponse;
import ru.taska.api.project.v1.ChangeProjectMemberRoleRequest;
import ru.taska.api.project.v1.ChangeProjectMemberRoleResponse;
import ru.taska.api.project.v1.CreateProjectRequest;
import ru.taska.api.project.v1.GetProjectRequest;
import ru.taska.api.project.v1.ListMyProjectsRequest;
import ru.taska.api.project.v1.ListMyProjectsResponse;
import ru.taska.api.project.v1.ProjectResponse;
import ru.taska.api.project.v1.ProjectRole;
import ru.taska.api.project.v1.ReactorProjectServiceGrpc;
import ru.taska.api.project.v1.RmProjectMemberRequest;
import ru.taska.api.project.v1.RmProjectMemberResponse;
import ru.taska.config.props.GrpcClientProperties;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.GatewayUserContext;
import ru.taska.domain.dto.AddProjectMemberRequestDto;
import ru.taska.domain.dto.ChangeProjectMemberRoleRequestDto;
import ru.taska.domain.dto.CreateProjectRequestDto;
import ru.taska.domain.dto.ListMyProjectResponseDto;
import ru.taska.domain.dto.ProjectMemberResponseDto;
import ru.taska.domain.dto.ProjectResponseDto;
import ru.taska.mapper.ProjectMapper;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@ExtendWith(MockitoExtension.class)
public class GrpcProjectServiceClientTest {

    private static final String REQUEST_ID = "req-001";
    private static final String NODE_ID = "api-gateway";
    private static final String PROJECT_ID = "6d774efa-57d8-4ae0-a27e-2984d1dfbbf6";
    private static final String USER_ID = "d221b01d-9c5b-4c3b-b3be-b5502f9d1a12";
    private static final String MEMBER_ID = "7adceb90-d1ea-4e32-bda7-9c9bd8aa8ef5";
    private static final String PROJECT_KEY = "TASKA";
    private static final String PROJECT_NAME = "Taska Platform";

    @Mock
    private ReactorProjectServiceGrpc.ReactorProjectServiceStub stub;

    @Mock
    private ProjectMapper projectMapper;

    @Mock
    private GrpcClientProperties properties;

    @Mock
    private GrpcClientProperties.Service projectService;

    @InjectMocks
    private GrpcProjectServiceClient client;

    private GatewayContext context;

    @BeforeEach
    void setUp() {
        context = new GatewayContext(
                REQUEST_ID,
                NODE_ID,
                GatewayUserContext.builder()
                        .userId(USER_ID)
                        .build()
        );

        Mockito.when(properties.projectService())
                .thenReturn(projectService);

        Mockito.when(projectService.deadlineDuration())
                .thenReturn(Duration.ofMillis(100));

        Mockito.when(stub.withDeadlineAfter(Mockito.anyLong(), Mockito.any(TimeUnit.class)))
                .thenReturn(stub);
    }

    // ========== CREATE PROJECT ==========

    @Test
    @DisplayName("Должен вызвать gRPC createProject и вернуть ответ")
    void createProject_shouldCallStubAndReturnMappedResponse() {
        // given
        var restRequest = new CreateProjectRequestDto();
        restRequest.setProjectKey(PROJECT_KEY);
        restRequest.setName(PROJECT_NAME);

        var grpcResponse = ProjectResponse.getDefaultInstance();
        var restResponse = new ProjectResponseDto();

        Mockito.when(stub.createProject(Mockito.any(CreateProjectRequest.class)))
                .thenReturn(Mono.just(grpcResponse));

        Mockito.when(projectMapper.toRestProjectResponse(grpcResponse))
                .thenReturn(restResponse);

        // when
        StepVerifier.create(client.createProject(Mono.just(restRequest), context))
                .expectNext(restResponse)
                .verifyComplete();

        // then
        var captor = ArgumentCaptor.forClass(CreateProjectRequest.class);
        Mockito.verify(stub).createProject(captor.capture());

        var request = captor.getValue();
        Assertions.assertThat(request.getHeader().getRequestId()).isEqualTo(REQUEST_ID);
        Assertions.assertThat(request.getHeader().getNodeId()).isEqualTo(NODE_ID);
        Assertions.assertThat(request.getBody().getProjectKey()).isEqualTo(PROJECT_KEY);
        Assertions.assertThat(request.getBody().getName()).isEqualTo(PROJECT_NAME);
        Assertions.assertThat(request.getBody().getUserId()).isEqualTo(USER_ID);

        Mockito.verify(projectMapper, Mockito.times(1))
                .toRestProjectResponse(grpcResponse);
    }

    // ========== GET PROJECT ==========

    @Test
    @DisplayName("Должен вызвать gRPC getProject и вернуть ответ")
    void getProject_shouldCallStubAndReturnMappedResponse() {
        // given
        var grpcResponse = ProjectResponse.getDefaultInstance();
        var restResponse = new ProjectResponseDto();

        Mockito.when(stub.getProject(Mockito.any(GetProjectRequest.class)))
                .thenReturn(Mono.just(grpcResponse));

        Mockito.when(projectMapper.toRestProjectResponse(grpcResponse))
                .thenReturn(restResponse);

        // when
        StepVerifier.create(client.getProject(PROJECT_ID, context))
                .expectNext(restResponse)
                .verifyComplete();

        // then
        var captor = ArgumentCaptor.forClass(GetProjectRequest.class);
        Mockito.verify(stub).getProject(captor.capture());

        var request = captor.getValue();
        Assertions.assertThat(request.getHeader().getRequestId()).isEqualTo(REQUEST_ID);
        Assertions.assertThat(request.getHeader().getNodeId()).isEqualTo(NODE_ID);
        Assertions.assertThat(request.getBody().getProjectId()).isEqualTo(PROJECT_ID);
        Assertions.assertThat(request.getBody().getActorUserId()).isEqualTo(USER_ID); // auth-aware!

        Mockito.verify(projectMapper, Mockito.times(1))
                .toRestProjectResponse(grpcResponse);
    }

    // ========== LIST MY PROJECTS ==========

    @Test
    @DisplayName("Должен вызвать gRPC listMyProjects и вернуть ответ")
    void listMyProjects_shouldCallStubAndReturnMappedResponse() {
        // given
        var grpcResponse = ListMyProjectsResponse.getDefaultInstance();
        var restResponse = new ListMyProjectResponseDto();

        Mockito.when(stub.listMyProjects(Mockito.any(ListMyProjectsRequest.class)))
                .thenReturn(Mono.just(grpcResponse));

        Mockito.when(projectMapper.toRestListMyProjectsResponse(grpcResponse))
                .thenReturn(restResponse);

        // when
        StepVerifier.create(client.listMyProjects(context))
                .expectNext(restResponse)
                .verifyComplete();

        // then
        var captor = ArgumentCaptor.forClass(ListMyProjectsRequest.class);
        Mockito.verify(stub).listMyProjects(captor.capture());

        var request = captor.getValue();
        Assertions.assertThat(request.getHeader().getRequestId()).isEqualTo(REQUEST_ID);
        Assertions.assertThat(request.getHeader().getNodeId()).isEqualTo(NODE_ID);
        Assertions.assertThat(request.getBody().getUserId()).isEqualTo(USER_ID);

        Mockito.verify(projectMapper, Mockito.times(1))
                .toRestListMyProjectsResponse(grpcResponse);
    }

    // ========== ADD PROJECT MEMBER ==========

    @Test
    @DisplayName("Должен вызвать gRPC addProjectMember и вернуть ответ")
    void addProjectMember_shouldCallStubAndReturnMappedResponse() {
        // given
        var restRequest = new AddProjectMemberRequestDto();
        restRequest.setUserId(MEMBER_ID);
        restRequest.setRole(AddProjectMemberRequestDto.RoleEnum.MEMBER);

        var grpcResponse = AddProjectMemberResponse.newBuilder()
                .setProjectId(PROJECT_ID)
                .setAddedMemberId(MEMBER_ID)
                .setRole(ProjectRole.PROJECT_ROLE_MEMBER)
                .build();
        var restResponse = new ProjectMemberResponseDto();
        restResponse.setProjectId(PROJECT_ID);
        restResponse.setUserId(MEMBER_ID);
        restResponse.setRole("MEMBER");

        Mockito.when(projectMapper.toGrpcProjectRole("MEMBER"))
                .thenReturn(ProjectRole.PROJECT_ROLE_MEMBER);

        Mockito.when(stub.addProjectMember(Mockito.any(AddProjectMemberRequest.class)))
                .thenReturn(Mono.just(grpcResponse));

        Mockito.when(projectMapper.toRestAddProjectMemberResponse(grpcResponse))
                .thenReturn(restResponse);

        // when
        StepVerifier.create(client.addProjectMember(PROJECT_ID, Mono.just(restRequest), context))
                .expectNext(restResponse)
                .verifyComplete();

        // then
        var captor = ArgumentCaptor.forClass(AddProjectMemberRequest.class);
        Mockito.verify(stub).addProjectMember(captor.capture());

        var request = captor.getValue();
        Assertions.assertThat(request.getHeader().getRequestId()).isEqualTo(REQUEST_ID);
        Assertions.assertThat(request.getHeader().getNodeId()).isEqualTo(NODE_ID);
        Assertions.assertThat(request.getBody().getProjectId()).isEqualTo(PROJECT_ID);
        Assertions.assertThat(request.getBody().getAddedMemberId()).isEqualTo(MEMBER_ID);
        Assertions.assertThat(request.getBody().getActorUserId()).isEqualTo(USER_ID);
        Assertions.assertThat(request.getBody().getRole()).isEqualTo(ProjectRole.PROJECT_ROLE_MEMBER);

        Mockito.verify(projectMapper, Mockito.times(1))
                .toRestAddProjectMemberResponse(grpcResponse);
    }

    // ========== CHANGE PROJECT MEMBER ROLE ==========

    @Test
    @DisplayName("Должен вызвать gRPC changeProjectMemberRole и вернуть ответ")
    void changeProjectMemberRole_shouldCallStubAndReturnMappedResponse() {
        // given
        var restRequest = new ChangeProjectMemberRoleRequestDto();
        restRequest.setRole(ChangeProjectMemberRoleRequestDto.RoleEnum.ADMIN);

        var grpcResponse = ChangeProjectMemberRoleResponse.newBuilder()
                .setProjectId(PROJECT_ID)
                .setChangedMemberId(MEMBER_ID)
                .setRole(ProjectRole.PROJECT_ROLE_ADMIN)
                .build();
        var restResponse = new ProjectMemberResponseDto();
                restResponse.setProjectId(PROJECT_ID);
                restResponse.setUserId(MEMBER_ID);
                restResponse.setRole("ADMIN");

        Mockito.when(projectMapper.toGrpcProjectRole("ADMIN"))
                .thenReturn(ProjectRole.PROJECT_ROLE_ADMIN);

        Mockito.when(stub.changeProjectMemberRole(Mockito.any(ChangeProjectMemberRoleRequest.class)))
                .thenReturn(Mono.just(grpcResponse));

        Mockito.when(projectMapper.toRestChangeProjectMemberRoleResponse(grpcResponse))
                .thenReturn(restResponse);

        // when
        StepVerifier.create(client.changeProjectMemberRole(PROJECT_ID, MEMBER_ID, Mono.just(restRequest), context))
                .expectNext(restResponse)
                .verifyComplete();

        // then
        var captor = ArgumentCaptor.forClass(ChangeProjectMemberRoleRequest.class);
        Mockito.verify(stub).changeProjectMemberRole(captor.capture());

        var request = captor.getValue();
        Assertions.assertThat(request.getHeader().getRequestId()).isEqualTo(REQUEST_ID);
        Assertions.assertThat(request.getHeader().getNodeId()).isEqualTo(NODE_ID);
        Assertions.assertThat(request.getBody().getProjectId()).isEqualTo(PROJECT_ID);
        Assertions.assertThat(request.getBody().getChangedMemberId()).isEqualTo(MEMBER_ID);
        Assertions.assertThat(request.getBody().getActorUserId()).isEqualTo(USER_ID);
        Assertions.assertThat(request.getBody().getRole()).isEqualTo(ProjectRole.PROJECT_ROLE_ADMIN);

        Mockito.verify(projectMapper, Mockito.times(1))
                .toRestChangeProjectMemberRoleResponse(grpcResponse);
    }

    // ========== REMOVE PROJECT MEMBER ==========

    @Test
    @DisplayName("Должен вызвать gRPC rmProjectMember и вернуть пустой Mono")
    void removeProjectMember_shouldCallStubAndReturnEmpty() {
        // given
        var grpcResponse = RmProjectMemberResponse.getDefaultInstance();

        Mockito.when(stub.rmProjectMember(Mockito.any(RmProjectMemberRequest.class)))
                .thenReturn(Mono.just(grpcResponse));

        // when
        StepVerifier.create(client.removeProjectMember(PROJECT_ID, MEMBER_ID, context))
                .verifyComplete();

        // then
        var captor = ArgumentCaptor.forClass(RmProjectMemberRequest.class);
        Mockito.verify(stub).rmProjectMember(captor.capture());

        var request = captor.getValue();
        Assertions.assertThat(request.getHeader().getRequestId()).isEqualTo(REQUEST_ID);
        Assertions.assertThat(request.getHeader().getNodeId()).isEqualTo(NODE_ID);
        Assertions.assertThat(request.getBody().getProjectId()).isEqualTo(PROJECT_ID);
        Assertions.assertThat(request.getBody().getDeletedMemberId()).isEqualTo(MEMBER_ID);
        Assertions.assertThat(request.getBody().getActorUserId()).isEqualTo(USER_ID);
    }

    // ========== ОБРАБОТКА ОШИБОК ==========

    @Test
    @DisplayName("Должен пробросить gRPC ошибку при создании проекта")
    void createProject_shouldPropagateGrpcError() {
        // given
        var restRequest = new CreateProjectRequestDto();
        restRequest.setProjectKey(PROJECT_KEY);
        restRequest.setName(PROJECT_NAME);

        Mockito.when(stub.createProject(Mockito.any(CreateProjectRequest.class)))
                .thenReturn(Mono.error(new io.grpc.StatusRuntimeException(io.grpc.Status.ALREADY_EXISTS)));

        // when & then
        StepVerifier.create(client.createProject(Mono.just(restRequest), context))
                .expectError(io.grpc.StatusRuntimeException.class)
                .verify();
    }
}
