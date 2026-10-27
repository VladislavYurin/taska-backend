package client;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;
import ru.taska.api.issue.v1.IssueShortResponse;
import ru.taska.api.project.v1.ChangeProjectIssueTypeRequest;
import ru.taska.api.project.v1.ProjectIssueTypeResponse;

/** TAS-432 expand-contract of the registry look, shared by every reader of ProjectIssueTypeResponse. */
class ProjectIssueTypesLookTest {
    private static final Set<String> ICONS =
            Set.of("check-square", "bug", "book-open", "headset", "megaphone", "file-text", "flag", "layers");

    @Test
    void wireFieldsKeepTheirPublishedNumbers() {
        var type = ProjectIssueTypeResponse.getDescriptor();
        assertThat(type.findFieldByName("legacy_icon").getNumber()).isEqualTo(4);
        assertThat(type.findFieldByName("shape").getNumber()).isEqualTo(7);
        assertThat(type.findFieldByName("icon").getNumber()).isEqualTo(8);
        var change = ChangeProjectIssueTypeRequest.getDescriptor();
        assertThat(change.findFieldByName("icon").getNumber()).isEqualTo(7);
        assertThat(change.findFieldByName("shape").getNumber()).isEqualTo(9);
        assertThat(change.findFieldByName("clear_icon").getNumber()).isEqualTo(10);
        var hit = IssueShortResponse.getDescriptor();
        assertThat(hit.findFieldByName("issue_type_icon").getNumber()).isEqualTo(14);
        assertThat(hit.findFieldByName("issue_type_shape").getNumber()).isEqualTo(15);
    }

    @Test
    void writerAlwaysGivesOldReadersOneOfTheEightIcons() {
        for (String code : new String[]{"TASK", "BUG", "STORY", "REQUEST"}) {
            var plain = ProjectIssueTypes.writeLook(ProjectIssueTypeResponse.newBuilder().setCode(code), code, "square", null).build();
            assertThat(plain.getLegacyIcon()).isIn(ICONS);
            assertThat(plain.hasIcon()).isFalse();
        }
        assertThat(ProjectIssueTypes.writeLook(ProjectIssueTypeResponse.newBuilder(), "BUG", "circle", null).getLegacyIcon())
                .isEqualTo("bug");
        var withIcon = ProjectIssueTypes.writeLook(ProjectIssueTypeResponse.newBuilder(), "TASK", "diamond", "flag").build();
        assertThat(withIcon.getLegacyIcon()).isEqualTo("flag");
        assertThat(withIcon.getIcon()).isEqualTo("flag");
    }

    @Test
    void newReaderTrustsTas432WriterAndFallsBackForAnOldOne() {
        var plainBug = ProjectIssueTypes.writeLook(ProjectIssueTypeResponse.newBuilder(), "BUG", "circle", null).build();
        assertThat(ProjectIssueTypes.icon(plainBug)).isNull();
        assertThat(ProjectIssueTypes.shape(plainBug)).isEqualTo("circle");

        var oldWriter = ProjectIssueTypeResponse.newBuilder().setCode("BUG").setLegacyIcon("bug").build();
        assertThat(ProjectIssueTypes.icon(oldWriter)).isEqualTo("bug");
        assertThat(ProjectIssueTypes.shape(oldWriter)).isEqualTo("square");

        var codeOnly = ProjectIssueTypeResponse.newBuilder().setCode("TASK").build();
        assertThat(ProjectIssueTypes.icon(codeOnly)).isNull();
        assertThat(ProjectIssueTypes.shape(codeOnly)).isEqualTo("square");
    }
}
