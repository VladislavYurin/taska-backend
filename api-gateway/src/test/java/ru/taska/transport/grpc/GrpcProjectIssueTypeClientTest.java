package ru.taska.transport.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import ru.taska.api.project.v1.ChangeProjectIssueTypeRequest;
import ru.taska.api.project.v1.ProjectIssueTypeResponse;
import ru.taska.api.project.v1.ReactorProjectServiceGrpc;
import ru.taska.config.props.GrpcClientProperties;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.GatewayUserContext;
import ru.taska.domain.dto.CreateProjectIssueTypeRequestDto;
import ru.taska.domain.dto.PatchProjectIssueTypeRequestDto;
import ru.taska.domain.dto.ProjectIssueTypeIconDto;
import ru.taska.domain.dto.ProjectIssueTypeShapeDto;
import tools.jackson.databind.json.JsonMapper;

/** TAS-432: shape and the clearable icon between REST and the registry. */
class GrpcProjectIssueTypeClientTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final ReactorProjectServiceGrpc.ReactorProjectServiceStub stub = mock(ReactorProjectServiceGrpc.ReactorProjectServiceStub.class);
    private final UUID project = UUID.randomUUID();
    private final GatewayContext context = new GatewayContext("r", "n",
            GatewayUserContext.builder().userId(UUID.randomUUID().toString()).build());
    private GrpcProjectIssueTypeClient client;

    @BeforeEach
    void setUp() {
        var service = new GrpcClientProperties.Service(null, 0, Duration.ofSeconds(1));
        client = new GrpcProjectIssueTypeClient(stub, new GrpcClientProperties(null, null, service, null, null, null));
        when(stub.withDeadlineAfter(anyLong(), any(TimeUnit.class))).thenReturn(stub);
        var answer = ProjectIssueTypeResponse.newBuilder().setGeneration(UUID.randomUUID().toString()).setCode("REQUEST").setName("Request").setColor("teal")
                .setShape("square").setLegacyIcon("check-square").setSortOrder(10).build();
        when(stub.updateProjectIssueType(any(ChangeProjectIssueTypeRequest.class))).thenReturn(Mono.just(answer));
        when(stub.createProjectIssueTypeWithWorkflow(any(ChangeProjectIssueTypeRequest.class))).thenReturn(Mono.just(
                ru.taska.api.project.v1.CreateProjectIssueTypeWithWorkflowResult.newBuilder()
                        .setIssueType(answer).setWorkflowId(project.toString()).build()));
    }

    @Test
    void explicitNullIconTravelsAsClearIconAndAbsentIconAsNothing() {
        assertThat(patch("{\"icon\":null}")).satisfies(request -> {
            assertThat(request.getClearIcon()).isTrue();
            assertThat(request.hasIcon()).isFalse();
        });
        assertThat(patch("{\"name\":\"Request\"}")).satisfies(request -> {
            assertThat(request.hasName()).isTrue();
            assertThat(request.getClearIcon()).isFalse();
            assertThat(request.hasIcon()).isFalse();
            assertThat(request.hasShape()).isFalse();
        });
        assertThat(patch("{\"icon\":\"flag\",\"shape\":\"diamond\"}")).satisfies(request -> {
            assertThat(request.getClearIcon()).isFalse();
            assertThat(request.getIcon()).isEqualTo("flag");
            assertThat(request.getShape()).isEqualTo("diamond");
        });
        // An empty body stays empty; the registry refuses it with "At least one field is required".
        assertThat(patch("{}")).satisfies(request -> {
            assertThat(request.getClearIcon()).isFalse();
            assertThat(request.hasIcon() || request.hasShape() || request.hasName()).isFalse();
        });
    }

    @Test
    void createSendsShapeAndIconOnlyWhenGiven() {
        var plain = json.readValue("{\"code\":\"REQUEST\",\"name\":\"Request\",\"color\":\"teal\",\"icon\":null}",
                CreateProjectIssueTypeRequestDto.class);
        client.create(project, plain, context).block();
        var full = json.readValue("{\"code\":\"REQUEST\",\"name\":\"Request\",\"color\":\"teal\",\"shape\":\"circle\",\"icon\":\"flag\"}",
                CreateProjectIssueTypeRequestDto.class);
        full.setWorkflowId(project.toString());
        var created = client.create(project, full, context).block();
        assertThat(created.getWorkflowId()).isEqualTo(project);
        var captor = ArgumentCaptor.forClass(ChangeProjectIssueTypeRequest.class);
        verify(stub, times(2)).createProjectIssueTypeWithWorkflow(captor.capture());
        assertThat(captor.getAllValues().get(0).hasWorkflowId()).isFalse();
        assertThat(captor.getAllValues().get(1).getWorkflowId()).isEqualTo(project.toString());
        assertThat(captor.getAllValues().get(0).hasShape()).isFalse();
        assertThat(captor.getAllValues().get(0).hasIcon()).isFalse();
        assertThat(captor.getAllValues().get(1).getShape()).isEqualTo("circle");
        assertThat(captor.getAllValues().get(1).getIcon()).isEqualTo("flag");
    }

    @Test
    void registryEntryWithoutIconMapsToNullIconAndItsShape() {
        var dto = GrpcProjectIssueTypeClient.toRest(ProjectIssueTypeResponse.newBuilder().setGeneration(UUID.randomUUID().toString()).setCode("BUG").setName("Bug")
                .setColor("red").setShape("circle").setLegacyIcon("bug").setSortOrder(20).build());
        assertThat(dto.getIcon()).isNull();
        assertThat(dto.getShape()).isEqualTo(ProjectIssueTypeShapeDto.CIRCLE);
        var withIcon = GrpcProjectIssueTypeClient.toRest(ProjectIssueTypeResponse.newBuilder().setGeneration(UUID.randomUUID().toString()).setCode("REQUEST")
                .setName("Request").setColor("teal").setShape("diamond").setIcon("headset").setLegacyIcon("headset").build());
        assertThat(withIcon.getIcon()).isEqualTo(ProjectIssueTypeIconDto.HEADSET);
        assertThat(withIcon.getShape()).isEqualTo(ProjectIssueTypeShapeDto.DIAMOND);
    }

    @Test
    void preTas432RegistryAnswerReadsAsSquareWithItsIcon() {
        // An old project-service only fills field 4 (now legacy_icon) and knows nothing about shape.
        var dto = GrpcProjectIssueTypeClient.toRest(ProjectIssueTypeResponse.newBuilder().setGeneration(UUID.randomUUID().toString()).setCode("BUG").setName("Bug")
                .setColor("red").setLegacyIcon("bug").setSortOrder(20).build());
        assertThat(dto.getIcon()).isEqualTo(ProjectIssueTypeIconDto.BUG);
        assertThat(dto.getShape()).isEqualTo(ProjectIssueTypeShapeDto.SQUARE);
    }

    private ChangeProjectIssueTypeRequest patch(String body) {
        clearInvocations(stub);
        client.update(project, "REQUEST", json.readValue(body, PatchProjectIssueTypeRequestDto.class), context).block();
        var captor = ArgumentCaptor.forClass(ChangeProjectIssueTypeRequest.class);
        verify(stub).updateProjectIssueType(captor.capture());
        return captor.getValue();
    }
}
