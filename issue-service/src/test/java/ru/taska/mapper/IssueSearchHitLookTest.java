package ru.taska.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.taska.domain.IssueType;
import ru.taska.domain.aggregate.IssueWithProject;
import ru.taska.domain.dto.IssueTypeMetadata;
import ru.taska.domain.dto.ProjectInfo;
import ru.taska.domain.entity.Issue;
import ru.taska.domain.entity.IssuePriority;
import tools.jackson.databind.json.JsonMapper;

/** TAS-432: the search hit takes its look from the project registry, icon only when the type has one. */
class IssueSearchHitLookTest {
    private final IssueMapper mapper = new IssueMapper(JsonMapper.builder().build());

    @Test
    void hitCarriesShapeAndNoIconForAPlainType() {
        var hit = mapper.toIssueShortProto(IssueWithProject.of(issue(IssueType.BUG), project(Map.of(
                "BUG", new IssueTypeMetadata("Bug", "red", null, "circle")))));
        assertThat(hit.getIssueTypeName()).isEqualTo("Bug");
        assertThat(hit.getIssueTypeColor()).isEqualTo("red");
        assertThat(hit.getIssueTypeShape()).isEqualTo("circle");
        assertThat(hit.hasIssueTypeIcon()).isFalse();
    }

    @Test
    void hitCarriesShapeAndIconForATypeWithOne() {
        var hit = mapper.toIssueShortProto(IssueWithProject.of(issue(IssueType.valueOf("MILESTONE")), project(Map.of(
                "MILESTONE", new IssueTypeMetadata("Milestone", "amber", "flag", "diamond")))));
        assertThat(hit.getIssueTypeShape()).isEqualTo("diamond");
        assertThat(hit.getIssueTypeIcon()).isEqualTo("flag");
    }

    @Test
    void deletedTypeHasNoLookAtAll() {
        var hit = mapper.toIssueShortProto(IssueWithProject.of(issue(IssueType.TASK), project(Map.of())));
        assertThat(hit.hasIssueTypeName()).isFalse();
        assertThat(hit.hasIssueTypeShape()).isFalse();
        assertThat(hit.hasIssueTypeIcon()).isFalse();
    }

    private Issue issue(IssueType type) {
        return Issue.builder().id(UUID.randomUUID()).issueKey("TAS-1").summary("Summary").issueType(type)
                .priority(IssuePriority.MEDIUM).statusKey("TODO").build();
    }

    private ProjectInfo project(Map<String, IssueTypeMetadata> types) {
        return new ProjectInfo(UUID.randomUUID().toString(), "TAS", "Taska", types);
    }
}
