package validator;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;
import ru.taska.api.issue.v1.IssueType;

import static org.assertj.core.api.Assertions.*;

class IssueTypeKeysTest {
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
