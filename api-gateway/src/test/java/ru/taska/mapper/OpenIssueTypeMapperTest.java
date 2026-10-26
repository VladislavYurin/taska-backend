package ru.taska.mapper;

import org.junit.jupiter.api.Test;
import ru.taska.api.issue.v1.IssuePriority;
import ru.taska.api.issue.v1.IssueResponse;
import ru.taska.api.issue.v1.IssueType;
import ru.taska.api.workflow.v1.ProjectWorkflow;
import ru.taska.api.workflow.v1.WorkflowResponse;
import ru.taska.domain.GatewayContext;
import ru.taska.domain.GatewayUserContext;
import ru.taska.domain.dto.CreateIssueRequestDto;
import ru.taska.domain.dto.CreateWorkflowRequestDto;
import ru.taska.domain.dto.IssuePriorityDto;
import ru.taska.domain.dto.PatchIssueRequestDto;
import ru.taska.domain.dto.SearchIssuesRequestDto;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class OpenIssueTypeMapperTest {
    private final ObjectMapper json = new ObjectMapper();
    private final IssueMapper mapper = new IssueMapper(json, null, null, null);
    private final WorkflowMapper workflow = new WorkflowMapper();
    private final GatewayContext context = new GatewayContext("r", "n", GatewayUserContext.builder()
            .userId(UUID.randomUUID().toString()).build());
    private final UUID project = UUID.randomUUID();

    @Test
    void createAndSearchWriteOpenKeyBesideLegacyField() {
        var body = new CreateIssueRequestDto("REQUEST", "Request", "", IssuePriorityDto.MEDIUM);
        var request = mapper.toCreateIssueGrpcRequest(project.toString(), "key", body, context);
        assertThat(request.getBody().getIssueTypeKey()).isEqualTo("REQUEST");
        assertThat(request.getBody().getIssueType()).isEqualTo(IssueType.ISSUE_TYPE_UNSPECIFIED);
        body.setIssueType("BUG");
        request = mapper.toCreateIssueGrpcRequest(project.toString(), "key", body, context);
        assertThat(request.getBody().getIssueTypeKey()).isEqualTo("BUG");
        assertThat(request.getBody().getIssueType()).isEqualTo(IssueType.ISSUE_TYPE_BUG);
        var search = mapper.toSearchIssuesGrpcRequest(new SearchIssuesRequestDto().issueType("NOPE"), context);
        assertThat(search.getBody().getIssueTypeKey()).isEqualTo("NOPE");
    }

    @Test
    void readsKeyInPreferenceToLegacyAndSupportsLegacyOnlyResponses() {
        var response = IssueResponse.newBuilder().setId(UUID.randomUUID().toString()).setIssueTypeKey("REQUEST")
                .setIssueType(IssueType.ISSUE_TYPE_TASK).setPriority(IssuePriority.ISSUE_PRIORITY_MEDIUM).build();
        assertThat(mapper.toRestIssueResponse(response).getIssueType()).isEqualTo("REQUEST");
        assertThat(mapper.toRestIssueResponse(response.toBuilder().clearIssueTypeKey().build()).getIssueType()).isEqualTo("TASK");
    }

    @Test
    void patchRejectsNullAndMalformedTypeKeys() {
        var patch = json.readValue("{\"issueType\":\"REQUEST\"}", PatchIssueRequestDto.class);
        assertThat(mapper.toPatchIssueRequest("id", "1", patch, context).getBody().getIssueTypeKey()).isEqualTo("REQUEST");
        for (String value : new String[]{"null", "\"request\"", "\"A\""}) {
            var bad = json.readValue("{\"issueType\":" + value + "}", PatchIssueRequestDto.class);
            assertThatThrownBy(() -> mapper.toPatchIssueRequest("id", "1", bad, context))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        }
        assertThat(mapper.toPatchIssueRequest("id", "1", new PatchIssueRequestDto(), context).getBody().hasIssueTypeKey()).isFalse();
    }

    @Test
    void createAndPatchRejectMalformedKeysConsistently() {
        for (String key : List.of("request", "A", "A".repeat(33), "WITH SPACE")) {
            var create = new CreateIssueRequestDto(key, "Request", "", IssuePriorityDto.MEDIUM);
            var patch = new PatchIssueRequestDto();
            patch.setIssueType(key);
            java.util.List<org.assertj.core.api.ThrowableAssert.ThrowingCallable> actions = List.of(
                    () -> mapper.toCreateIssueGrpcRequest(project.toString(), "key", create, context),
                    () -> mapper.toPatchIssueRequest("id", "1", patch, context));
            for (var action : actions) {
                assertThatThrownBy(action).isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
                        error -> {
                            assertThat(error.getStatusCode().value()).isEqualTo(400);
                            assertThat(error.getReason()).isEqualTo("Invalid issue type key");
                        });
            }
        }
    }

    @Test
    void upstreamKeysAreForwardedWithoutParsingTheWholeResponse() {
        var response = IssueResponse.newBuilder().setId(UUID.randomUUID().toString()).setIssueTypeKey("legacy-request")
                .setPriority(IssuePriority.ISSUE_PRIORITY_MEDIUM).build();
        assertThat(mapper.toRestIssueResponse(response).getIssueType()).isEqualTo("legacy-request");
    }

    @Test
    void workflowCreationReadAndMutationEnvelopesRetainCustomKeys() {
        var dto = new CreateWorkflowRequestDto().name("Requests").issueTypes(List.of("REQUEST"))
                .statuses(List.of()).transitions(List.of());
        var created = workflow.toCreateWorkflowGrpcRequest(project, dto, context);
        assertThat(created.getBody().getIssueTypeKeysList()).containsExactly("REQUEST");
        assertThat(workflow.toGetWorkflowGrpcRequest(project, "REQUEST", context).getBody().getIssueTypeKey()).isEqualTo("REQUEST");
        var response = WorkflowResponse.newBuilder().setId(UUID.randomUUID().toString()).addIssueTypeKeys("REQUEST").build();
        assertThat(workflow.toMutationResponseRestDto(response).getIssueTypes()).containsExactly("REQUEST");
        assertThat(workflow.toProjectWorkflowDto(ProjectWorkflow.newBuilder().setWorkflow(response).addIssueTypeKeys("REQUEST").build())
                .getIssueTypes()).containsExactly("REQUEST");
    }
}
