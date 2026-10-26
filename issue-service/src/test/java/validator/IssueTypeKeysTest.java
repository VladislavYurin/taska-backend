package validator;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;
import ru.taska.api.issue.v1.IssueType;

import static org.assertj.core.api.Assertions.*;

class IssueTypeKeysTest {
    @Test
    void boardWireFieldsKeepTheirPublishedNumbers() {
        var request = ru.taska.api.issue.v1.ListIssuesForBoardRequestBody.getDescriptor();
        assertThat(request.findFieldByName("priority").getNumber()).isEqualTo(10);
        assertThat(request.findFieldByName("query").getNumber()).isEqualTo(11);
        assertThat(request.findFieldByName("issue_type_key").getNumber()).isEqualTo(12);
        var response = ru.taska.api.issue.v1.IssueBoardResponse.getDescriptor();
        assertThat(response.findFieldByName("description").getNumber()).isEqualTo(12);
        assertThat(response.findFieldByName("attachment_count").getNumber()).isEqualTo(18);
        assertThat(response.findFieldByName("issue_type_key").getNumber()).isEqualTo(19);
    }

    @Test
    void prefersKeyOverLegacyAndSupportsLegacyOnlyClients() {
        assertThat(IssueTypeKeys.read(true, "REQUEST", IssueType.ISSUE_TYPE_TASK).name()).isEqualTo("REQUEST");
        assertThat(IssueTypeKeys.read(false, "", IssueType.ISSUE_TYPE_BUG).name()).isEqualTo("BUG");
        assertThat(IssueTypeKeys.optional(false, "", false, IssueType.ISSUE_TYPE_UNSPECIFIED).block()).isEmpty();
    }

    @Test
    void explicitlyInvalidKeyCannotFallBackToLegacyOrRemoveAFilter() {
        for (String key : new String[]{"", "request", "A", "A".repeat(33)}) {
            assertThatThrownBy(() -> IssueTypeKeys.read(true, key, IssueType.ISSUE_TYPE_TASK))
                    .isInstanceOfSatisfying(StatusRuntimeException.class,
                            e -> assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INVALID_ARGUMENT));
        }
        assertThatThrownBy(() -> IssueTypeKeys.read(false, "", IssueType.ISSUE_TYPE_UNSPECIFIED))
                .isInstanceOf(StatusRuntimeException.class);
    }
}
